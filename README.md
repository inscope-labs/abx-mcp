# ABC Server (Auto Bridge Context Server)

ABC Server is a native Android security client implementing the ABC Server architecture (Specification Revision 3.0). It provides a hardware-backed, session-gated, capability-scoped bridge between an on-device Model Context Protocol (MCP) tool runtime and the local filesystem, with full append-only cryptographically signed audit logging.

The internal Kotlin package structure uses the namespace `com.inscopelabs.abxmcp` as an internal codename and package identifier, while all user-facing surfaces and external references are fully branded as **ABC Server**.

## Key Features

- **Hardware-Backed Key Generation & Attestation**: Non-exportable, secure hardware-backed key material with cryptographic attestation generated inside the Android KeyStore.
- **Session-Gated Lifecycles**: Explicit local gesture requirements (e.g., button press) to activate or extend session state, with secure, background coroutine-driven TTL countdowns.
- **Granular Capability Scope & Policy Engine**: Strict authorization rules mapped to capability tokens (including allowed operations and directory allowed roots).
- **Time-of-Check to Time-of-Use (TOCTOU) Defenses**: The MCP Executor uses the verified canonical path resolved by the Policy Engine, preventing path traversal, symlink-swapping exploits, and content:// scheme leaks.
- **Hardened Replay Protection**: Synchronized timestamp-based nonce tracking with automatic background eviction to defend against session replay attacks under sustained loads.
- **Foreground Service Status**: Live notification channel with API 33+ runtime permission requesting and graceful fallback.

## Module Map

The architecture is split into clean, single-responsibility modules:

| Module | Description |
| :--- | :--- |
| `:app` | Jetpack Compose UI, main activity, permission request flows, and general app orchestration. |
| `:core:keystore` | Secure cryptographic key management, Android KeyStore interactions, and attestation. |
| `:core:session` | Session state machine, local-gesture requirements, and active/expired transition engines. |
| `:core:tunnel` | Background `cloudflared` tunnel process management, foreground status notifications, and live StateFlow updates. |
| `:core:policy` | Authorization rules, path resolution/normalization, and allowed directory root matching. |
| `:core:filesystem` | Safe file access operations interacting directly with the filesystem. |
| `:core:mcp` | Model Context Protocol tool execution, request validation, and JSON RPC parsers. |
| `:core:audit` | Append-only, hash-chained, and signed audit logging for all filesystem operations. |

## Building & Installation

This project is developed inside Google AI Studio and contains **no committed Gradle wrapper**. The project utilizes modern Android development practices, using Kotlin DSL (`build.gradle.kts`) and Jetpack Compose.

### Local Development in Android Studio

1. **Clone & Open**: Clone the repository and open the root directory in Android Studio. Let the IDE sync dependencies automatically.
2. **Environment Variables**: Create a `.env` file in the root directory following `.env.example` if you are using external integrations or custom debug credentials.
3. **Run**: Deploy to a physical device or emulator.
   * *Note on Emulators*: `core:keystore` automatically falls back to an in-memory mock keystore if it detects an emulator environment (e.g. `goldfish` or `ranchu`), bypassing actual hardware-backed requirements for testing convenience. Real TEE/StrongBox behaviors require deployment on a physical Android device.

## CI Requirements & GitHub Actions

The continuous integration pipeline is defined in `.github/workflows/build.yml`.
- **No Gradle Wrapper**: Since there is no `gradlew` script in the repository, the CI environment uses `gradle/actions/setup-gradle` to automatically provision the correct Gradle runtime.
- **Build Verification**: Compiles both debug and release configurations.
- **Local JVM Testing**: Runs all local JUnit, Robolectric, and Roborazzi screenshot/visual verification tests on every push.

## Project Phase Status

This project has completed all core development and remediation phases:

- **Phases 1-5**: Core functional implementation of key management, local session gating, `cloudflared` tunnel lifecycle, policy validation, MCP JSON-RPC executors, and cryptographically chained audit logging.
- **Phase 6.3 (Tunnel Lifecycle Truthfulness Remediation)**: Removed silent run fallbacks; implemented real binary checking on `libcloudflared.so` using ELF magic headers.
- **Phase 6.4 (Path Semantics & Replay Hardening Remediation)**: Prevented TOCTOU symlink-swapping attacks by passing authorized canonical paths from the Policy Engine directly to raw I/O readers; explicitly rejected non-file schemes (e.g., `content://`); implemented bounded-memory concurrent nonce caching.
- **Phase 6.5 (Manifest & Permission Correctness)**: Added explicit manifest declarations for `INTERNET` and `POST_NOTIFICATIONS`; introduced an API 33+ dynamic permission request flow with graceful non-blocking behavior.
- **Phase 6.6 (Branding & Documentation Consistency)**: Rebranded all user-facing strings, notifications, clipboard formats, and titles to **ABC Server**.

## Testing

Run unit tests and Robolectric checks locally:
```bash
gradle :app:testDebugUnitTest
```
