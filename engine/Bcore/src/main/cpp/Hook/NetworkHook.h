//
// NetworkHook — contención de DNS y sockets para las apps virtualizadas.
//

#ifndef BLACKBOX_NETWORKHOOK_H
#define BLACKBOX_NETWORKHOOK_H

#include <jni.h>

namespace NetworkHook {

    // Debe coincidir con OsStub.POLICY_* del lado Java.
    enum Policy {
        POLICY_BLOCK = 0,  // indeterminable -> denegar
        POLICY_TOR   = 1,  // todo el tráfico por el SOCKS5 de Tor
        POLICY_DOH   = 2   // sin Tor, pero resolviendo por DoH
    };

    /** Instala los hooks de libc. Idempotente. */
    void init(JNIEnv *env);

    /** Fija la política del proceso actual (una app virtual = un proceso). */
    void setPolicy(int policy);
}

#endif //BLACKBOX_NETWORKHOOK_H
