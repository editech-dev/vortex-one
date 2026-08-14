# 🤖 AI Agent Orientation & Development Guide - Vortex One (v2.0.0)

Welcome to **Vortex One (MediaService)**. This document serves as the authoritative guide for AI assistants, subagents, and automated workflows working on this codebase. It outlines the project architecture, coding standards, subagent directory, skill loading mechanisms, and workflow expectations.

---

## 📌 Project Overview & Architecture

**Vortex One** is an Android virtualization and privacy hub designed for both **Android TV** and **Mobile Devices**. It runs isolated virtual instances of Android apps (cloned or sideloaded APKs), enforces per-app **Tor routing**, provides **DNS-over-TLS (DoT)**, and inspects real-time traffic via a built-in **Firewall**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Vortex One Core App                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                              MainActivity                             │  │
│  │          (Dashboard with 16:9 Grid ViewBinding Layout for TV)         │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
│                                      │                                      │
│           ┌──────────────────────────┴──────────────────────────┐           │
│           ▼                                                     ▼           │
│   FileScannerActivity                                   SettingsActivity    │
│   (APK Installer & USB)                             (Storage & GMS Control) │
│           │                                                     │           │
│           └──────────────────────────┬──────────────────────────┘           │
│                                      ▼                                      │
│                            com.editech.services                             │
│                                      │                                      │
│         ┌────────────────────────────┼────────────────────────────┐         │
│         ▼                            ▼                            ▼         │
│   VirtualApp Mgr             Firewall Manager                Tor Manager    │
│  (App Launcher)             (Room DB + NetMon)             (Native libtor)  │
└─────────┬────────────────────────────┬────────────────────────────┬─────────┘
          │                            │                            │
          ▼                            ▼                            ▼
    ┌───────────┐              ┌───────────────┐            ┌──────────────┐
    │Engine Core│              │  Firewall DB  │            │  Tor Daemon  │
    │ (:engine) │              │ (Room Kotlin) │            │ (SOCKS5 9050)│
    └───────────┘              └───────────────┘            └──────────────┘
```

### Module Topology:
- **`:app`**: Kotlin-based application module containing UI screens, activities, adapters, Room DB firewall logic, Tor service management, and DoT DNS resolution.
- **`:engine:Bcore`**: Core virtualization library written in low-level Java (based on BlackBox), including AIDL interface stubs, binder hooks (`ILocaleManagerProxy`, `GmsProxy`, `IInAppBillingServiceProxy`, `BPackageManager`), and process isolation logic.
- **`:engine:black-reflection`**: Reflection utilities (`FreeReflection` / `BlackReflection`) for accessing internal Android APIs.
- **`:engine:compiler`**: Annotation processor for engine reflection mapping.

---

## 📜 Core AI Coding Guidelines

### 1. Multi-Device Layout & UI Rules
- **No Jetpack Compose**: The UI must strictly use **XML ViewBinding** to ensure high performance and low CPU/RAM overhead on Android TV hardware.
- **Android TV D-Pad First**: All interactive elements in layouts must support D-Pad focus (`android:focusable="true"`, `android:clickable="true"`). Use state selectors with high contrast glowing focus states (`@drawable/selector_*`).
- **Mobile Responsive**: Ensure touch targets are at least **48x48dp** for phone touchscreen operation.
- **Adaptive Grids**: Manage dashboard layouts dynamically with `GridLayoutManager`.

### 2. Engine Integrity & Java Code Safety
- **Keep Engine Code in Java**: Code inside `:engine:Bcore` and `:engine:black-reflection` MUST remain Java. Do NOT attempt to migrate engine stubs or AIDL-generated classes to Kotlin, as exact Java method signatures, native JNI bindings, and reflective accesses are required.
- **Preserve Reflection Contracts**: Avoid renaming or modifying reflectively accessed symbols in `engine/`.

### 3. Asynchronous & Network Rules
- **No Main Looper Blocking**: Never perform database IO (Room), file system scans, socket checks, or heavy reflection on the main UI thread (`Dispatchers.Main`). Use Kotlin Coroutines with `Dispatchers.IO`.
- **Firewall & Tor Isolation**: Ensure `FirewallManager` and `NetworkConnectionMonitor` log network connections asynchronously without disrupting active virtual app processes.

### 4. Dependency & Build Logic
- **Build Toolchain**: JDK 17+, Android Gradle Plugin, Min SDK 21, Target SDK 34, Android NDK 25.x.
- **Build Variant Commands**:
  - Debug Build: `./gradlew assembleDebug`
  - Release Build: `./gradlew assembleRelease`
  - Test Execution: `./gradlew test`

---

## 🧠 Project Skills Ecosystem (`.agents/skills/`)

Skills provide specialized domain knowledge, coding standards, and step-by-step procedures for AI agents. 

> [!IMPORTANT]
> **SKILL ACTIVATION PROTOCOL**: Before performing any complex task, AI agents and subagents **MUST** inspect the relevant `SKILL.md` file using `view_file` at `.agents/skills/<skill-name>/SKILL.md` to load guidelines, guardrails, and code patterns.

### Available Workspace Skills & Subagent Alignment:

| Skill Name | Path | Description | Recommended Subagent |
| :--- | :--- | :--- | :--- |
| **`android-architecture-clean`** | [.agents/skills/android-architecture-clean/SKILL.md](file:///.agents/skills/android-architecture-clean/SKILL.md) | Clean architecture boundaries, repositories, use cases, and presentation layers. | `ui-ux-designer`, `firewall-network-engineer` |
| **`android-coroutines-flow`** | [.agents/skills/android-coroutines-flow/SKILL.md](file:///.agents/skills/android-coroutines-flow/SKILL.md) | Coroutines, Flow pipelines, Dispatchers.IO, structured concurrency, and async cancellation. | `firewall-network-engineer` |
| **`android-gradle-build-logic`** | [.agents/skills/android-gradle-build-logic/SKILL.md](file:///.agents/skills/android-gradle-build-logic/SKILL.md) | Gradle build logic, version catalogs, ProGuard/R8 obfuscation, and plugins. | `release-publisher`, `virtualization-engine-developer` |
| **`android-kotlin-core`** | [.agents/skills/android-kotlin-core/SKILL.md](file:///.agents/skills/android-kotlin-core/SKILL.md) | Idiomatic Kotlin usage, data classes, nullability safety, and collection pipelines. | `ui-ux-designer`, `firewall-network-engineer` |
| **`android-networking-retrofit-okhttp`** | [.agents/skills/android-networking-retrofit-okhttp/SKILL.md](file:///.agents/skills/android-networking-retrofit-okhttp/SKILL.md) | Network contracts, OkHttp interceptors, network logging, and socket connection safety. | `firewall-network-engineer` |
| **`android-testing-unit`** | [.agents/skills/android-testing-unit/SKILL.md](file:///.agents/skills/android-testing-unit/SKILL.md) | Unit tests for ViewModels, repositories, use cases, and Room DB test doubles. | `virtualization-engine-developer`, `firewall-network-engineer` |
| **`java-coding-standards`** | [.agents/skills/java-coding-standards/SKILL.md](file:///.agents/skills/java-coding-standards/SKILL.md) | Java coding standards, immutability, Optional, generics, and framework stubs. | `virtualization-engine-developer` |
| **`java-docs`** | [.agents/skills/java-docs/SKILL.md](file:///.agents/skills/java-docs/SKILL.md) | Javadoc comments and type documentation for Java engine code. | `virtualization-engine-developer` |

---

## 🤖 Subagent Directory (`.agents/agents/`)

When assigning specialized tasks, delegate them to the corresponding specialized subagent:

| Subagent | Path | Specialized Domain & Scope | When to Invoke |
| :--- | :--- | :--- | :--- |
| **`virtualization-engine-developer`** | [AGENT.md](file:///.agents/agents/virtualization-engine-developer/AGENT.md) | `:engine:Bcore`, AIDL stubs, `BPackageManager` Leanback, `ILocaleManagerProxy`, `GmsProxy`, `IInAppBillingServiceProxy`, `VirtualSpoof.cpp` GPU passthrough. | Modifying virtual sandbox, IPC bindings, system service hooks, or native C++ JNI code. |
| **`firewall-network-engineer`** | [AGENT.md](file:///.agents/agents/firewall-network-engineer/AGENT.md) | `com.editech.services.firewall`, Room DB, `OsStub.java` libc socket hooks, `TorManager`, `TorService` (embedded `libtor.so`), `CloudflareDnsResolver` (DoT port 853). | Managing network security, Tor per-app privacy, DNS-over-TLS, socket traffic rules, and connection logs. |
| **`ui-ux-designer`** | [AGENT.md](file:///.agents/agents/ui-ux-designer/AGENT.md) | Android XML ViewBinding layouts, D-Pad remote focus indicators (`#38BDF8`), TV 16:9 adaptive grids, Material cards. | Designing and styling activities, fragments, dialogs, adapters, and theme resources. |
| **`git-manager`** | [AGENT.md](file:///.agents/agents/git-manager/AGENT.md) | Git version control, targeted staging, diff analysis, Conventional Commits without commercial app names. | Preparing commits, organizing branch changes, and maintaining a clean commit history. |
| **`release-publisher`** | [AGENT.md](file:///.agents/agents/release-publisher/AGENT.md) | Gradle release compilation, `versionCode`/`versionName` synchronization, ProGuard/R8 obfuscation, SHA-256 generation, Git tagging & GitHub Releases. | Automating release candidate creation, release verification, and binary deployment. |
