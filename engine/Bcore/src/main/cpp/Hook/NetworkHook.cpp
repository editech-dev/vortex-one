//
// NetworkHook — contención de DNS y sockets para las apps virtualizadas.
//
// Los hooks Java de OsStub sólo alcanzan a libcore.io.Os, es decir, al código
// que resuelve y conecta a través de la capa Java. Un runtime nativo —el VM de
// Dart, la pila de red de Chromium, libmpv/ffmpeg— llama a getaddrinfo() y
// connect() de libc directamente y nunca pasa por ellos, así que su DNS acaba
// en el resolver del sistema y sus sockets salen a Internet sin tunelizar.
//
// Este archivo cierra esa vía interceptando los símbolos en libc dentro de cada
// proceso de app virtual. El demonio Tor vive en el proceso principal, no en
// uno de estos, de modo que jamás se hookea a sí mismo y no hay bucle posible.
//

#include "NetworkHook.h"
#include "BoxCore.h"
#include "xdl.h"
#include "Dobby/dobby.h"

#include <android/log.h>
#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/uio.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <string>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <thread>
#include <mutex>
#include <poll.h>

#define LOG_TAG "NetworkHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace {

    // Puertos locales del demonio Tor.
    constexpr uint16_t TOR_SOCKS_PORT   = 9150;
    constexpr uint16_t TOR_CONTROL_PORT = 9151;
    constexpr uint16_t TOR_DNS_PORT     = 5453;

    // Tope de la negociación SOCKS. Con un circuito ya montado Tor responde en
    // centenares de ms; el margen cubre un exit lento sin acercarse al umbral
    // de ANR (5 s), que es lo que importa porque se espera en línea.
    constexpr int HANDSHAKE_TIMEOUT_SECS = 3;

    // Por defecto DoH: contenido (nunca el resolver del sistema) pero sin
    // bloquear, para no dejar sin red al proceso servidor de BlackBox, que
    // también carga esta librería. handleBindApplication fija la política real
    // de cada app virtual antes de que su código llegue a ejecutarse.
    std::atomic<int> g_policy{NetworkHook::POLICY_DOH};
    std::atomic<bool> g_installed{false};

    // Reentrada: mientras resolvemos por DoH llamamos a Java, que a su vez abre
    // sockets. Sin esta marca esas llamadas volverían a entrar en los hooks.
    thread_local bool t_inHook = false;

    struct HookGuard {
        bool active;
        HookGuard() : active(!t_inHook) { if (active) t_inHook = true; }
        ~HookGuard() { if (active) t_inHook = false; }
    };

    // ── Mapa de IPs virtuales 127.192.0.0/10 ────────────────────────────────
    // Mismo esquema que OsStub.getOrAllocateVirtualIp: bajo Tor no se resuelve
    // nada localmente; se entrega una IP ficticia y el nombre real viaja luego
    // dentro del CONNECT SOCKS5, de modo que quien resuelve es el nodo de salida.
    std::mutex g_mapMutex;
    std::unordered_map<std::string, std::string> g_hostToVirtual;
    std::unordered_map<std::string, std::string> g_virtualToHost;
    uint32_t g_virtualCounter = 1;

    std::string allocateVirtualIp(const char *host) {
        std::lock_guard<std::mutex> lock(g_mapMutex);
        auto it = g_hostToVirtual.find(host);
        if (it != g_hostToVirtual.end()) return it->second;

        if (g_virtualCounter > 0xFFFE) g_virtualCounter = 1;
        uint32_t n = g_virtualCounter++;
        char buf[32];
        snprintf(buf, sizeof(buf), "127.192.%u.%u", (n >> 8) & 0xFF, n & 0xFF);

        std::string virt(buf);
        g_hostToVirtual[host] = virt;
        g_virtualToHost[virt] = host;
        return virt;
    }

    bool lookupVirtualHost(const char *virtualIp, std::string &out) {
        std::lock_guard<std::mutex> lock(g_mapMutex);
        auto it = g_virtualToHost.find(virtualIp);
        if (it == g_virtualToHost.end()) return false;
        out = it->second;
        return true;
    }

    bool isVirtualIp(uint32_t hostOrderAddr) {
        // 127.192.0.0/10
        return (hostOrderAddr & 0xFFC00000u) == 0x7FC00000u;
    }

    bool isNumericHost(const char *node) {
        if (node == nullptr || *node == '\0') return true;
        unsigned char buf[16];
        if (inet_pton(AF_INET, node, buf) == 1) return true;
        if (inet_pton(AF_INET6, node, buf) == 1) return true;
        return false;
    }

    // ── Punteros a los símbolos originales ──────────────────────────────────
    int (*orig_getaddrinfo)(const char *, const char *,
                            const struct addrinfo *, struct addrinfo **) = nullptr;
    int (*orig_getaddrinfofornet)(const char *, const char *,
                                  const struct addrinfo *, unsigned, unsigned,
                                  struct addrinfo **) = nullptr;
    int (*orig_connect)(int, const struct sockaddr *, socklen_t) = nullptr;
    ssize_t (*orig_sendto)(int, const void *, size_t, int,
                           const struct sockaddr *, socklen_t) = nullptr;

    // ── DoH: se delega en el resolver Kotlin, que valida TLS ────────────────
    //
    // La clase y el método se resuelven una sola vez, durante init(), que corre
    // en un hilo Java de la app. Buscarlos en cada llamada no sirve: cuando la
    // petición nace en un hilo nativo (el VM de Dart, la red de Chromium),
    // FindClass usa el classloader del sistema, que no ve las clases de la app
    // y devolvería siempre "no encontrada".
    jclass g_nativeCoreClass = nullptr;   // referencia global
    jmethodID g_resolveDohMethod = nullptr;

    void cacheDohBridge(JNIEnv *env) {
        if (g_nativeCoreClass != nullptr) return;
        jclass local = env->FindClass(VMCORE_CLASS);
        if (local == nullptr) {
            env->ExceptionClear();
            LOGW("no se pudo localizar NativeCore; el DoH quedará inoperativo");
            return;
        }
        g_nativeCoreClass = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        g_resolveDohMethod = env->GetStaticMethodID(
                g_nativeCoreClass, "resolveViaDoH", "(Ljava/lang/String;)Ljava/lang/String;");
        if (g_resolveDohMethod == nullptr) {
            env->ExceptionClear();
            LOGW("no se pudo localizar NativeCore.resolveViaDoH");
        } else {
            LOGD("puente DoH preparado");
        }
    }

    bool resolveViaDoH(const char *host, std::string &out) {
        if (g_nativeCoreClass == nullptr || g_resolveDohMethod == nullptr) {
            LOGW("puente DoH no disponible");
            return false;
        }
        JavaVM *vm = BoxCore::getJavaVM();
        if (vm == nullptr) return false;

        JNIEnv *env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
            attached = true;
        }

        bool ok = false;
        jstring jhost = env->NewStringUTF(host);
        auto jres = (jstring) env->CallStaticObjectMethod(
                g_nativeCoreClass, g_resolveDohMethod, jhost);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else if (jres != nullptr) {
            const char *chars = env->GetStringUTFChars(jres, nullptr);
            if (chars != nullptr) {
                out.assign(chars);
                env->ReleaseStringUTFChars(jres, chars);
                ok = !out.empty();
            }
            env->DeleteLocalRef(jres);
        }
        env->DeleteLocalRef(jhost);

        if (attached) vm->DetachCurrentThread();
        return ok;
    }

    // ── Resolución contenida, compartida por ambos getaddrinfo ──────────────
    // Se apoya en el getaddrinfo original con AI_NUMERICHOST para que sea bionic
    // quien construya y libere la cadena de addrinfo. Fabricarla a mano es la
    // vía rápida a un fallo en freeaddrinfo().
    int callOriginalNumeric(const char *numeric, const char *service,
                            const struct addrinfo *hints, struct addrinfo **res,
                            bool forNet, unsigned netid, unsigned mark) {
        struct addrinfo numericHints{};
        if (hints != nullptr) {
            numericHints = *hints;
        } else {
            numericHints.ai_family = AF_UNSPEC;
        }
        numericHints.ai_flags |= AI_NUMERICHOST;
        numericHints.ai_family = AF_INET;
        numericHints.ai_addrlen = 0;
        numericHints.ai_addr = nullptr;
        numericHints.ai_canonname = nullptr;
        numericHints.ai_next = nullptr;

        if (forNet && orig_getaddrinfofornet != nullptr) {
            return orig_getaddrinfofornet(numeric, service, &numericHints, netid, mark, res);
        }
        return orig_getaddrinfo(numeric, service, &numericHints, res);
    }

    /**
     * ¿Designa este nombre a la propia máquina?
     *
     * Varias apps montan un servidor en loopback y le hablan por nombre; en
     * StreamBridge es el proxy que alimenta al reproductor. Virtualizar esos
     * nombres los mandaría por Tor, donde no existen, y rompería justo la ruta
     * de vídeo. Se resuelven como lo que son: la máquina local.
     */
    bool isLocalName(const char *node) {
        if (node == nullptr) return false;
        return strcasecmp(node, "localhost") == 0 ||
               strcasecmp(node, "localhost.localdomain") == 0 ||
               strcasecmp(node, "ip6-localhost") == 0;
    }

    int containedResolve(const char *node, const char *service,
                         const struct addrinfo *hints, struct addrinfo **res,
                         bool forNet, unsigned netid, unsigned mark) {
        int policy = g_policy.load();

        // El propio equipo nunca sale a la red, sea cual sea la política.
        if (isLocalName(node)) {
            return callOriginalNumeric("127.0.0.1", service, hints, res,
                                       forNet, netid, mark);
        }

        if (policy == NetworkHook::POLICY_TOR) {
            std::string virt = allocateVirtualIp(node);
            LOGD("[dns] %s -> %s (tor)", node, virt.c_str());
            return callOriginalNumeric(virt.c_str(), service, hints, res, forNet, netid, mark);
        }

        if (policy == NetworkHook::POLICY_DOH) {
            std::string ip;
            {
                HookGuard guard;
                if (resolveViaDoH(node, ip)) {
                    LOGD("[dns] %s -> %s (doh)", node, ip.c_str());
                    return callOriginalNumeric(ip.c_str(), service, hints, res, forNet, netid, mark);
                }
            }
            LOGW("[dns] DoH no resolvió %s; se deniega en vez de usar el DNS del sistema", node);
            return EAI_FAIL;
        }

        LOGW("[dns] política indeterminada; se deniega %s", node);
        return EAI_FAIL;
    }

    // ── SOCKS5 ──────────────────────────────────────────────────────────────
    // Estas dos usan los símbolos originales a propósito: read() y write() están
    // hookeados y responden EAGAIN mientras el handshake está en curso, de modo
    // que llamarlos aquí haría que la negociación se bloquease a sí misma.
    bool writeFull(int fd, const uint8_t *buf, size_t len) {
        size_t sent = 0;
        while (sent < len) {
            ssize_t n = write(fd, buf + sent, len - sent);
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;
                return false;
            }
            sent += (size_t) n;
        }
        return true;
    }

    bool readFull(int fd, uint8_t *buf, size_t len) {
        size_t got = 0;
        while (got < len) {
            ssize_t n = read(fd, buf + got, len - got);
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;
                return false;
            }
            got += (size_t) n;
        }
        return true;
    }

    /** Handshake RFC 1928 sobre un fd ya conectado al proxy. */
    bool socks5Handshake(int fd, const char *hostname,
                         const uint8_t *ipv4, uint16_t port) {
        uint8_t greeting[3] = {0x05, 0x01, 0x00};   // VER, NMETHODS=1, NO_AUTH
        if (!writeFull(fd, greeting, sizeof(greeting))) {
            LOGW("[socks] saludo no enviado (%s)", strerror(errno));
            return false;
        }

        uint8_t methodReply[2];
        if (!readFull(fd, methodReply, sizeof(methodReply))) {
            LOGW("[socks] sin respuesta al saludo (%s)", strerror(errno));
            return false;
        }
        if (methodReply[0] != 0x05 || methodReply[1] != 0x00) {
            LOGW("[socks] autenticación rechazada: 0x%02x", methodReply[1]);
            return false;
        }

        uint8_t request[262];
        size_t n = 0;
        request[n++] = 0x05;  // VER
        request[n++] = 0x01;  // CMD = CONNECT
        request[n++] = 0x00;  // RSV

        if (hostname != nullptr) {
            // ATYP=0x03: el nombre lo resuelve el nodo de salida, no nosotros.
            size_t hlen = strlen(hostname);
            if (hlen == 0 || hlen > 255) return false;
            request[n++] = 0x03;
            request[n++] = (uint8_t) hlen;
            memcpy(request + n, hostname, hlen);
            n += hlen;
        } else if (ipv4 != nullptr) {
            request[n++] = 0x01;
            memcpy(request + n, ipv4, 4);
            n += 4;
        } else {
            return false;
        }

        request[n++] = (uint8_t) ((port >> 8) & 0xFF);
        request[n++] = (uint8_t) (port & 0xFF);

        if (!writeFull(fd, request, n)) {
            LOGW("[socks] CONNECT no enviado (%s)", strerror(errno));
            return false;
        }

        uint8_t reply[4];
        if (!readFull(fd, reply, sizeof(reply))) {
            LOGW("[socks] sin respuesta al CONNECT: %s (%s)",
                 hostname != nullptr ? hostname : "ip-literal", strerror(errno));
            return false;
        }
        if (reply[0] != 0x05 || reply[1] != 0x00) {
            LOGW("[socks] CONNECT rechazado: ver=0x%02x rep=0x%02x (%s)",
                 reply[0], reply[1],
                 hostname != nullptr ? hostname : "ip-literal");
            return false;
        }

        // Consumir BND.ADDR + BND.PORT según el tipo de dirección.
        uint8_t skip[256];
        switch (reply[3]) {
            case 0x01: if (!readFull(fd, skip, 4)) return false; break;
            case 0x04: if (!readFull(fd, skip, 16)) return false; break;
            case 0x03: {
                uint8_t len;
                if (!readFull(fd, &len, 1)) return false;
                if (len > 0 && !readFull(fd, skip, len)) return false;
                break;
            }
            default: return false;
        }
        if (!readFull(fd, skip, 2)) {   // BND.PORT
            LOGW("[socks] respuesta truncada");
            return false;
        }
        LOGD("[socks] túnel establecido: %s",
             hostname != nullptr ? hostname : "ip-literal");
        return true;
    }

    /** Ejecuta el handshake completo sobre un fd puesto en modo bloqueante. */
    bool performTorHandshake(int fd, const std::string &hostname,
                             const uint8_t ipv4[4], bool haveIpv4, uint16_t port) {
        // El socket puede ser AF_INET6: conectarlo a un sockaddr_in falla con
        // EAFNOSUPPORT, así que al proxy se le habla en la familia del socket.
        int domain = AF_INET;
        socklen_t dlen = sizeof(domain);
        getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &dlen);

        int rc;
        if (domain == AF_INET6) {
            struct sockaddr_in6 proxy6{};
            proxy6.sin6_family = AF_INET6;
            proxy6.sin6_port = htons(TOR_SOCKS_PORT);
            // ::ffff:127.0.0.1
            uint8_t *raw = (uint8_t *) &proxy6.sin6_addr;
            raw[10] = 0xFF; raw[11] = 0xFF;
            raw[12] = 127;  raw[13] = 0; raw[14] = 0; raw[15] = 1;
            rc = orig_connect(fd, (struct sockaddr *) &proxy6, sizeof(proxy6));
        } else {
            struct sockaddr_in proxy{};
            proxy.sin_family = AF_INET;
            proxy.sin_port = htons(TOR_SOCKS_PORT);
            proxy.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            rc = orig_connect(fd, (struct sockaddr *) &proxy, sizeof(proxy));
        }

        if (rc != 0) {
            // Kill-switch: sin túnel no se sale. Nunca se cae a conexión directa.
            LOGW("[tor] proxy inalcanzable; conexión bloqueada");
            return false;
        }
        return socks5Handshake(fd, hostname.empty() ? nullptr : hostname.c_str(),
                               haveIpv4 ? ipv4 : nullptr, port);
    }

    /** Aplica un tiempo máximo a la negociación para que un Tor colgado no la eternice. */
    void applyHandshakeTimeouts(int fd) {
        struct timeval tv{};
        tv.tv_sec = HANDSHAKE_TIMEOUT_SECS;
        tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    }

    // ── Relé local ──────────────────────────────────────────────────────────
    //
    // Ni negociar dentro de connect() ni hacerlo en un hilo sobre el socket de
    // la app son viables: lo primero retiene el hilo de Dart y acaba en ANR; lo
    // segundo compite por un descriptor cuyo dueño es la app, que lo cierra
    // cuando quiere y deja que el kernel recicle el número mientras seguimos
    // escribiéndole el saludo SOCKS.
    //
    // La salida es no tocar ese socket. Se levanta un puerto en loopback y el
    // connect() de la app se redirige ahí: para ella es una conexión local
    // corriente, que completa al instante y se comporta con normalidad ante
    // epoll. Del otro lado, un hilo nuestro abre su propio socket hacia Tor,
    // negocia con calma y pasa los bytes de un lado a otro.
    int g_relayFd = -1;
    uint16_t g_relayPort = 0;
    std::once_flag g_relayOnce;

    struct Destination {
        std::string hostname;
        uint8_t ipv4[4];
        bool haveIpv4;
        uint16_t port;
    };

    std::mutex g_destMutex;
    std::unordered_map<uint16_t, Destination> g_destinations;   // puerto origen -> destino

    void rememberDestination(uint16_t srcPort, const Destination &dest) {
        std::lock_guard<std::mutex> lock(g_destMutex);
        g_destinations[srcPort] = dest;
    }

    bool takeDestination(uint16_t srcPort, Destination &out) {
        std::lock_guard<std::mutex> lock(g_destMutex);
        auto it = g_destinations.find(srcPort);
        if (it == g_destinations.end()) return false;
        out = it->second;
        g_destinations.erase(it);
        return true;
    }

    /** Trasiega bytes entre los dos extremos hasta que uno se cierra. */
    void pump(int a, int b) {
        struct pollfd fds[2];
        fds[0].fd = a;
        fds[1].fd = b;
        uint8_t buf[16384];

        for (;;) {
            fds[0].events = POLLIN;
            fds[1].events = POLLIN;
            fds[0].revents = fds[1].revents = 0;

            if (poll(fds, 2, 300000) <= 0) return;   // 5 min de inactividad

            for (int i = 0; i < 2; ++i) {
                if (fds[i].revents & POLLIN) {
                    ssize_t n = read(fds[i].fd, buf, sizeof(buf));
                    if (n <= 0) return;
                    int dst = fds[1 - i].fd;
                    ssize_t sent = 0;
                    while (sent < n) {
                        ssize_t w = write(dst, buf + sent, (size_t) (n - sent));
                        if (w <= 0) {
                            if (w < 0 && errno == EINTR) continue;
                            return;
                        }
                        sent += w;
                    }
                }
                if (fds[i].revents & (POLLERR | POLLHUP | POLLNVAL)) return;
            }
        }
    }

    /** Atiende una conexión aceptada: abre el túnel y empalma. */
    void serveConnection(int appSide, Destination dest) {
        int torSide = socket(AF_INET, SOCK_STREAM, 0);
        if (torSide < 0) { close(appSide); return; }

        applyHandshakeTimeouts(torSide);

        struct sockaddr_in proxy{};
        proxy.sin_family = AF_INET;
        proxy.sin_port = htons(TOR_SOCKS_PORT);
        proxy.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

        bool ok = orig_connect(torSide, (struct sockaddr *) &proxy, sizeof(proxy)) == 0 &&
                  socks5Handshake(torSide,
                                  dest.hostname.empty() ? nullptr : dest.hostname.c_str(),
                                  dest.haveIpv4 ? dest.ipv4 : nullptr,
                                  dest.port);
        if (!ok) {
            // Kill-switch: sin túnel no se sale. Cerrar hace que la app vea el
            // fallo por su propio camino de error en vez de esperar sin fin.
            LOGW("[tor] túnel no establecido: %s",
                 dest.hostname.empty() ? "ip-literal" : dest.hostname.c_str());
            close(torSide);
            close(appSide);
            return;
        }

        // Ya negociado: a partir de aquí sólo se copian bytes, sin plazos.
        struct timeval none{};
        setsockopt(torSide, SOL_SOCKET, SO_SNDTIMEO, &none, sizeof(none));
        setsockopt(torSide, SOL_SOCKET, SO_RCVTIMEO, &none, sizeof(none));

        pump(appSide, torSide);
        close(torSide);
        close(appSide);
    }

    void acceptLoop() {
        for (;;) {
            struct sockaddr_in peer{};
            socklen_t plen = sizeof(peer);
            int appSide = accept(g_relayFd, (struct sockaddr *) &peer, &plen);
            if (appSide < 0) {
                if (errno == EINTR) continue;
                return;
            }

            Destination dest;
            if (!takeDestination(ntohs(peer.sin_port), dest)) {
                // Sin destino registrado no se puede saber a dónde iba: cerrar
                // es la única respuesta segura.
                close(appSide);
                continue;
            }
            std::thread(serveConnection, appSide, dest).detach();
        }
    }

    /** Levanta el relé una sola vez por proceso. Devuelve false si no pudo. */
    bool ensureRelay() {
        std::call_once(g_relayOnce, []() {
            int fd = socket(AF_INET, SOCK_STREAM, 0);
            if (fd < 0) return;
            int one = 1;
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

            struct sockaddr_in addr{};
            addr.sin_family = AF_INET;
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            addr.sin_port = 0;                       // puerto efímero
            if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) != 0 ||
                listen(fd, 128) != 0) {
                close(fd);
                return;
            }

            socklen_t alen = sizeof(addr);
            if (getsockname(fd, (struct sockaddr *) &addr, &alen) != 0) {
                close(fd);
                return;
            }

            g_relayFd = fd;
            g_relayPort = ntohs(addr.sin_port);
            std::thread(acceptLoop).detach();
            LOGD("relé local escuchando en 127.0.0.1:%u", g_relayPort);
        });
        return g_relayFd >= 0;
    }

    /**
     * Reencamina un connect() por el SOCKS5 de Tor.
     *
     * No se negocia nada aquí: el socket se ata a un puerto conocido, se anota
     * a dónde iba y se le redirige al relé local. Para la app es una conexión a
     * loopback, que completa de inmediato; la negociación con Tor ocurre al
     * otro lado del relé, sobre un socket nuestro, sin retener su hilo ni
     * disputarle el descriptor.
     */
    int connectViaTor(int fd, const char *hostname, const uint8_t *ipv4, uint16_t port) {
        if (!ensureRelay()) {
            LOGW("[tor] relé local no disponible; conexión bloqueada");
            errno = ECONNREFUSED;
            return -1;
        }

        int domain = AF_INET;
        socklen_t dlen = sizeof(domain);
        getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &dlen);
        if (domain != AF_INET) {
            // El relé escucha en IPv4. Un socket IPv6 no puede conectarse a él
            // salvo en modo doble pila, y no merece la pena adivinarlo.
            LOGW("[tor] socket no IPv4 hacia %s; conexión bloqueada",
                 hostname != nullptr ? hostname : "ip-literal");
            errno = EAFNOSUPPORT;
            return -1;
        }

        // Se fija el puerto de origen antes de conectar: es la etiqueta con la
        // que el relé reconocerá esta conexión al aceptarla.
        struct sockaddr_in local{};
        local.sin_family = AF_INET;
        local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        local.sin_port = 0;
        if (bind(fd, (struct sockaddr *) &local, sizeof(local)) != 0) {
            // Ya estaba atado; sirve igual mientras sepamos el puerto.
        }
        socklen_t llen = sizeof(local);
        if (getsockname(fd, (struct sockaddr *) &local, &llen) != 0 ||
            local.sin_port == 0) {
            LOGW("[tor] sin puerto de origen; conexión bloqueada");
            errno = ECONNREFUSED;
            return -1;
        }

        Destination dest;
        dest.hostname = (hostname != nullptr) ? std::string(hostname) : std::string();
        dest.haveIpv4 = (ipv4 != nullptr);
        if (dest.haveIpv4) memcpy(dest.ipv4, ipv4, 4); else memset(dest.ipv4, 0, 4);
        dest.port = port;
        rememberDestination(ntohs(local.sin_port), dest);

        struct sockaddr_in relay{};
        relay.sin_family = AF_INET;
        relay.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        relay.sin_port = htons(g_relayPort);
        return orig_connect(fd, (struct sockaddr *) &relay, sizeof(relay));
    }

    bool isDatagramSocket(int fd) {
        int type = 0;
        socklen_t len = sizeof(type);
        if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0) return false;
        return type == SOCK_DGRAM;
    }

    bool isLoopbackSockaddr(const struct sockaddr *addr) {
        if (addr == nullptr) return false;
        if (addr->sa_family == AF_INET) {
            auto *in4 = (const struct sockaddr_in *) addr;
            return (ntohl(in4->sin_addr.s_addr) >> 24) == 127;
        }
        if (addr->sa_family == AF_INET6) {
            auto *in6 = (const struct sockaddr_in6 *) addr;
            return IN6_IS_ADDR_LOOPBACK(&in6->sin6_addr) != 0;
        }
        return false;
    }

    // ── Hooks ───────────────────────────────────────────────────────────────
    int my_getaddrinfo(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **res) {
        if (t_inHook || isNumericHost(node) ||
            (hints != nullptr && (hints->ai_flags & AI_NUMERICHOST))) {
            return orig_getaddrinfo(node, service, hints, res);
        }
        return containedResolve(node, service, hints, res, false, 0, 0);
    }

    int my_getaddrinfofornet(const char *node, const char *service,
                             const struct addrinfo *hints, unsigned netid,
                             unsigned mark, struct addrinfo **res) {
        if (t_inHook || isNumericHost(node) ||
            (hints != nullptr && (hints->ai_flags & AI_NUMERICHOST))) {
            return orig_getaddrinfofornet(node, service, hints, netid, mark, res);
        }
        return containedResolve(node, service, hints, res, true, netid, mark);
    }

    int my_connect(int fd, const struct sockaddr *addr, socklen_t addrlen) {
        if (t_inHook || addr == nullptr) {
            return orig_connect(fd, addr, addrlen);
        }


        // Unix sockets y demás familias no nos incumben.
        if (addr->sa_family != AF_INET && addr->sa_family != AF_INET6) {
            return orig_connect(fd, addr, addrlen);
        }

        int policy = g_policy.load();
        uint16_t port = 0;
        uint32_t v4 = 0;

        // Un socket de doble pila entrega direcciones IPv4 envueltas en IPv6
        // (::ffff:a.b.c.d). Son IPv4 a todos los efectos, y tratarlas como IPv6
        // haría bloquear hasta el propio túnel, que vive en 127.0.0.1.
        bool haveV4 = false;
        if (addr->sa_family == AF_INET) {
            auto *in4 = (const struct sockaddr_in *) addr;
            port = ntohs(in4->sin_port);
            v4 = ntohl(in4->sin_addr.s_addr);
            haveV4 = true;
        } else {
            auto *in6 = (const struct sockaddr_in6 *) addr;
            port = ntohs(in6->sin6_port);
            if (IN6_IS_ADDR_V4MAPPED(&in6->sin6_addr)) {
                uint32_t netOrder;
                memcpy(&netOrder, ((const uint8_t *) &in6->sin6_addr) + 12, 4);
                v4 = ntohl(netOrder);
                haveV4 = true;
            }
        }

        bool loopback = haveV4 ? (((v4 >> 24) & 0xFF) == 127)
                               : isLoopbackSockaddr(addr);

        // El rango de IPs virtuales, 127.192.0.0/10, cae dentro de 127.0.0.0/8:
        // para el kernel son loopback. Si se tratasen como tales acabarían en un
        // orig_connect() contra una dirección local inexistente y toda conexión
        // de una app bajo Tor fallaría. Se distinguen antes de nada.
        bool virtualTarget = haveV4 && isVirtualIp(v4);

        if (loopback && !virtualTarget) {
            // El ControlPort permite reconfigurar el demonio (fijar ExitNode,
            // abrir el SocksPort al exterior) y enumerar circuitos. Ninguna app
            // del sandbox tiene motivo para hablar con él.
            if (port == TOR_CONTROL_PORT) {
                LOGW("[tor] acceso al ControlPort denegado");
                errno = EPERM;
                return -1;
            }
            // El SOCKS y el DNSPort pasan directos: son el propio túnel.
            return orig_connect(fd, addr, addrlen);
        }

        // Una IP virtual sólo tiene sentido bajo Tor: fuera de ahí no hay a qué
        // traducirla, y dejarla pasar sería conectar contra loopback.
        if (virtualTarget && policy != NetworkHook::POLICY_TOR) {
            LOGW("[net] IP virtual fuera de política Tor; conexión bloqueada");
            errno = EPERM;
            return -1;
        }

        if (policy == NetworkHook::POLICY_BLOCK) {
            LOGW("[net] política indeterminada; conexión bloqueada");
            errno = EPERM;
            return -1;
        }

        if (policy != NetworkHook::POLICY_TOR) {
            return orig_connect(fd, addr, addrlen);
        }

        // A partir de aquí la app va por Tor.

        // SOCKS5 CONNECT sólo transporta TCP. Un datagrama a destino público
        // saldría por fuera del túnel (QUIC/HTTP3, WebRTC/STUN, DNS directo),
        // así que se deniega para forzar la caída a TCP/443, que sí se tuneliza.
        // connect() a la dirección no especificada sobre UDP no envía nada:
        // es la forma estándar de deshacer la asociación del socket.
        if (haveV4 && v4 == 0) {
            return orig_connect(fd, addr, addrlen);
        }

        if (isDatagramSocket(fd)) {
            LOGW("[tor] UDP a destino público bloqueado: %u.%u.%u.%u:%u",
                 (v4 >> 24) & 0xFF, (v4 >> 16) & 0xFF, (v4 >> 8) & 0xFF, v4 & 0xFF, port);
            errno = EPERM;
            return -1;
        }

        // IPv6 nativo no se tuneliza: se deniega en vez de dejar un flanco
        // abierto. Las direcciones IPv4-mapeadas ya se han desenvuelto arriba.
        if (!haveV4) {
            LOGW("[tor] IPv6 bloqueado bajo Tor: puerto %u", port);
            errno = EPERM;
            return -1;
        }

        // Redes privadas y UPnP: bajo Tor no se tocan.
        uint8_t b1 = (v4 >> 24) & 0xFF, b2 = (v4 >> 16) & 0xFF;
        bool privateNet = (b1 == 10) ||
                          (b1 == 172 && b2 >= 16 && b2 <= 31) ||
                          (b1 == 192 && b2 == 168) ||
                          (b1 == 169 && b2 == 254);
        if (!virtualTarget && (privateNet || port == 1900 || port == 5351)) {
            LOGW("[tor] destino LAN/UPnP bloqueado: %u.%u.%u.%u:%u",
                 (v4 >> 24) & 0xFF, (v4 >> 16) & 0xFF, (v4 >> 8) & 0xFF, v4 & 0xFF, port);
            errno = EPERM;
            return -1;
        }

        if (virtualTarget) {
            char virtBuf[32];
            snprintf(virtBuf, sizeof(virtBuf), "%u.%u.%u.%u",
                     (v4 >> 24) & 0xFF, (v4 >> 16) & 0xFF, (v4 >> 8) & 0xFF, v4 & 0xFF);
            std::string hostname;
            if (lookupVirtualHost(virtBuf, hostname)) {
                LOGD("[tor] túnel -> %s:%u", hostname.c_str(), port);
                HookGuard guard;
                return connectViaTor(fd, hostname.c_str(), nullptr, port);
            }
            // IP virtual sin nombre asociado: no se puede resolver sin filtrar.
            LOGW("[tor] IP virtual %s sin nombre asociado", virtBuf);
            errno = EPERM;
            return -1;
        }

        // IP literal: la app la traía codificada, sin DNS de por medio.
        uint8_t raw[4] = {
                (uint8_t) ((v4 >> 24) & 0xFF), (uint8_t) ((v4 >> 16) & 0xFF),
                (uint8_t) ((v4 >> 8) & 0xFF),  (uint8_t) (v4 & 0xFF)
        };
        LOGD("[tor] túnel -> %u.%u.%u.%u:%u (IP literal)",
             raw[0], raw[1], raw[2], raw[3], port);
        HookGuard guard;
        return connectViaTor(fd, nullptr, raw, port);
    }

    ssize_t my_sendto(int fd, const void *buf, size_t len, int flags,
                      const struct sockaddr *dest, socklen_t addrlen) {
        if (!t_inHook && dest != nullptr &&
            g_policy.load() == NetworkHook::POLICY_TOR &&
            (dest->sa_family == AF_INET || dest->sa_family == AF_INET6) &&
            !isLoopbackSockaddr(dest) && isDatagramSocket(fd)) {
            if (dest->sa_family == AF_INET) {
                auto *d4 = (const struct sockaddr_in *) dest;
                uint32_t a = ntohl(d4->sin_addr.s_addr);
                LOGW("[tor] sendto UDP bloqueado: %u.%u.%u.%u:%u",
                     (a >> 24) & 0xFF, (a >> 16) & 0xFF, (a >> 8) & 0xFF, a & 0xFF,
                     ntohs(d4->sin_port));
            } else {
                auto *d6 = (const struct sockaddr_in6 *) dest;
                LOGW("[tor] sendto UDP IPv6 bloqueado: puerto %u", ntohs(d6->sin6_port));
            }
            errno = EPERM;
            return -1;
        }
        return orig_sendto(fd, buf, len, flags, dest, addrlen);
    }


    void hookSymbol(void *handle, const char *name, void *replacement, void **original) {
        void *target = xdl_dsym(handle, name, nullptr);
        if (target == nullptr) {
            LOGW("símbolo no encontrado: %s", name);
            return;
        }
        if (DobbyHook(target, replacement, original) == 0) {
            LOGD("hook instalado: %s", name);
        } else {
            LOGW("fallo al hookear: %s", name);
        }
    }

} // namespace

namespace NetworkHook {

    void init(JNIEnv *env) {
        bool expected = false;
        if (!g_installed.compare_exchange_strong(expected, true)) return;

        cacheDohBridge(env);

        void *handle = xdl_open("libc.so", XDL_DEFAULT);
        if (handle == nullptr) {
            LOGW("no se pudo abrir libc.so; el tráfico nativo quedaría sin contener");
            return;
        }

        hookSymbol(handle, "getaddrinfo", (void *) my_getaddrinfo, (void **) &orig_getaddrinfo);
        hookSymbol(handle, "android_getaddrinfofornet", (void *) my_getaddrinfofornet,
                   (void **) &orig_getaddrinfofornet);
        hookSymbol(handle, "connect", (void *) my_connect, (void **) &orig_connect);
        hookSymbol(handle, "sendto", (void *) my_sendto, (void **) &orig_sendto);

        xdl_close(handle);
        LOGD("NetworkHook instalado (política inicial=%d)", g_policy.load());
    }

    void setPolicy(int policy) {
        g_policy.store(policy);
        LOGD("política de red = %d", policy);
    }
}
