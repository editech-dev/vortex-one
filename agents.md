# 🤖 AI Agent Orientation & Development Guide - Vortex One

Welcome to **Vortex One (MediaService)**. This document serves as the authoritative guide for AI assistants, subagents, and automated workflows working on this codebase. It outlines the project architecture, coding standards, subagent directory, skill loading mechanisms, and workflow expectations.

---

## 📌 Project Overview & Architecture

**Vortex One** is an Android virtualization and entertainment hub designed for both **Android TV** and **Mobile Phones**. It allows running isolated virtual instances of Android apps (cloned apps) and managing internal network security via a built-in firewall.

```
┌─────────────────────────────────────────────────────────────┐
│                   Vortex One Core App                       │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                    MainActivity                       │  │
│  │    (Dashboard with 4-Column Grid ViewBinding Layout)  │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │                               │
│           ┌─────────────────┴─────────────────┐             │
│           ▼                                   ▼             │
│   FileScannerActivity               SystemAppsActivity      │
│   (APK Installer)                   (App Virtualizer)       │
│           │                                   │             │
│           └─────────────────┬─────────────────┘             │
│                             ▼                               │
│                   com.editech.services                      │
│                             │                               │
│         ┌───────────────────┼───────────────────┐           │
│         ▼                   ▼                   ▼           │
│   VirtualApp Mgr     Firewall Manager       AdManager       │
│   (App Launcher)     (Room DB + NetMon)    (Unity Ads)      │
└─────────┬───────────────────┬───────────────────┬───────────┘
          │                   │                   │
          ▼                   ▼                   ▼
    ┌───────────┐     ┌───────────────┐   ┌──────────────┐
    │Engine Core│     │  Firewall DB  │   │  Unity SDK   │
    │ (:engine) │     │ (Room Kotlin) │   │ (Ads Core)   │
    └───────────┘     └───────────────┘   └──────────────┘
```

### Module Topology:
- **`:app`**: Kotlin-based application module containing UI screens, activities, adapters, Room DB firewall logic, and application management.
- **`:engine:Bcore`**: Core virtualization library written in low-level Java (based on BlackBox), including AIDL interface stubs, binder hooks, and process isolation logic.
- **`:engine:black-reflection`**: Reflection utilities (`FreeReflection` / `BlackReflection`) for accessing internal Android APIs.
- **`:engine:compiler`**: Annotation processor for engine reflection mapping.

---

## 📜 Core AI Coding Guidelines

### 1. Multi-Device Layout & UI Rules
- **No Jetpack Compose**: The UI must strictly use **XML ViewBinding** to ensure high performance and low CPU/RAM overhead on Android TV hardware.
- **Android TV D-Pad First**: All interactive elements in layouts must support D-Pad focus (`android:focusable="true"`, `android:clickable="true"`). Use state selectors with high contrast glowing focus states (`@drawable/selector_*`).
- **Mobile Responsive**: Ensure touch targets are at least **48x48dp** for phone touchscreen operation.
- **Adaptive Grids**: Manage dashboard layouts dynamically with `GridLayoutManager` (referencing `grid_span_count` or `res/values/integers.xml`).

### 2. Engine Integrity & Java Code Safety
- **Keep Engine Code in Java**: Code inside `:engine:Bcore` and `:engine:black-reflection` MUST remain Java. Do NOT attempt to migrate engine stubs or AIDL-generated classes to Kotlin, as exact Java method signatures, native JNI bindings, and reflective accesses are required.
- **Preserve Reflection Contracts**: Avoid renaming or modifying reflectively accessed symbols in `engine/`.

### 3. Asynchronous & Network Rules
- **No Main Looper Blocking**: Never perform database IO (Room), file system scans, or heavy reflection on the main UI thread (`Dispatchers.Main`). Use Kotlin Coroutines with `Dispatchers.IO`.
- **Firewall Isolation**: Ensure `FirewallManager` and `NetworkConnectionMonitor` log network connections asynchronously without disrupting active virtual app processes.

### 4. Dependency & Build Logic
- **Build Toolchain**: JDK 17+, Android Gradle Plugin, Min SDK 21, Target SDK 34.
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
| **`android-compose-foundations`** | [.agents/skills/android-compose-foundations/SKILL.md](file:///.agents/skills/android-compose-foundations/SKILL.md) | Compose foundations *(Reference only; app strictly uses XML for TV)*. | `ui-ux-designer` |
| **`android-coroutines-flow`** | [.agents/skills/android-coroutines-flow/SKILL.md](file:///.agents/skills/android-coroutines-flow/SKILL.md) | Coroutines, Flow pipelines, Dispatchers.IO, structured concurrency, and async cancellation. | `firewall-network-engineer`, `test-engineer` |
| **`android-di-hilt`** | [.agents/skills/android-di-hilt/SKILL.md](file:///.agents/skills/android-di-hilt/SKILL.md) | Hilt dependency injection, scopes, modules, and testing overrides. | `firewall-network-engineer`, `ui-ux-designer` |
| **`android-gradle-build-logic`** | [.agents/skills/android-gradle-build-logic/SKILL.md](file:///.agents/skills/android-gradle-build-logic/SKILL.md) | Gradle build logic, version catalogs, ProGuard/R8 obfuscation, and plugins. | `release-publisher`, `virtualization-engine-developer` |
| **`android-kotlin-core`** | [.agents/skills/android-kotlin-core/SKILL.md](file:///.agents/skills/android-kotlin-core/SKILL.md) | Idiomatic Kotlin usage, data classes, nullability safety, and collection pipelines. | `ui-ux-designer`, `firewall-network-engineer`, `test-engineer` |
| **`android-networking-retrofit-okhttp`** | [.agents/skills/android-networking-retrofit-okhttp/SKILL.md](file:///.agents/skills/android-networking-retrofit-okhttp/SKILL.md) | Retrofit contracts, OkHttp interceptors, network logging, and error handling. | `firewall-network-engineer`, `security-auditor` |
| **`android-testing-unit`** | [.agents/skills/android-testing-unit/SKILL.md](file:///.agents/skills/android-testing-unit/SKILL.md) | Unit tests for ViewModels, repositories, use cases, and Room DB test doubles. | `test-engineer`, `security-auditor` |
| **`java-coding-standards`** | [.agents/skills/java-coding-standards/SKILL.md](file:///.agents/skills/java-coding-standards/SKILL.md) | Java coding standards, immutability, Optional, generics, and framework stubs. | `virtualization-engine-developer` |
| **`java-docs`** | [.agents/skills/java-docs/SKILL.md](file:///.agents/skills/java-docs/SKILL.md) | Javadoc comments and type documentation for Java engine code. | `virtualization-engine-developer` |

---

## 🤖 Subagent Directory (`.agents/agents/`)

When assigning specialized tasks, delegate them to the corresponding subagent:

| Subagent | Path | Specialized Skills | When to Invoke |
| :--- | :--- | :--- | :--- |
| **`ui-ux-designer`** | [AGENT.md](file:///.agents/agents/ui-ux-designer/AGENT.md) | `android-architecture-clean`, `android-kotlin-core` | Designing XML layouts, D-Pad focus indicators, TV/Mobile adapters, themes. |
| **`virtualization-engine-developer`** | [AGENT.md](file:///.agents/agents/virtualization-engine-developer/AGENT.md) | `java-coding-standards`, `java-docs`, `android-gradle-build-logic` | Modifying `:engine:Bcore`, Java AIDL stubs, JNI hooks, process sandbox, reflection. |
| **`firewall-network-engineer`** | [AGENT.md](file:///.agents/agents/firewall-network-engineer/AGENT.md) | `android-coroutines-flow`, `android-networking-retrofit-okhttp`, `android-di-hilt` | Working on `firewall/` package, Room DB entities/DAOs, connection logging, traffic rules. |
| **`security-auditor`** | [AGENT.md](file:///.agents/agents/security-auditor/AGENT.md) | `android-testing-unit`, `android-networking-retrofit-okhttp` | **Read-only** audits of sandbox isolation, permissions, Room DB security, dependencies. |
| **`test-engineer`** | [AGENT.md](file:///.agents/agents/test-engineer/AGENT.md) | `android-testing-unit`, `android-coroutines-flow` | Writing unit tests, mocking Bcore/Room dependencies, Robolectric, `./gradlew test`. |
| **`release-publisher`** | [AGENT.md](file:///.agents/agents/release-publisher/AGENT.md) | `android-gradle-build-logic` | Automating release builds, `versionCode`/`versionName` sync, ProGuard, SHA-256 checks, Git tagging & GitHub Releases (`editech-dev/vortex-one`). |
| **`git-manager`** | [AGENT.md](file:///.agents/agents/git-manager/AGENT.md) | N/A | Staging changes (`git add`), analyzing diffs, writing Conventional Commits. |
