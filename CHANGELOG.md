# Changelog

All notable changes to the ABC Server security client will be documented in this file.

## [1.5.0] - 2026-07-08

### Added
- **Phase 6.6: Branding & Documentation Consistency**:
  - Rebranded all user-facing strings, labels, and clipboard actions across `strings.xml`, `EnrollmentScreen`, `TunnelService` notifications/channels, and `KeyStoreManager` attestation challenges from "ABX-MCP" to "ABC Server".
  - Completely rewrote `README.md` to serve as highly polished, professional documentation of ABC Server's architecture, modules, building steps, CI details, and development phase progression.
  - Updated unit tests, platform `metadata.json`, and Gradle project settings to expect the rebranded app name and notification titles.
- **Phase 6.5: Manifest & Permission Correctness**:
  - Declared `android.permission.INTERNET` at the `:app` manifest level to enable necessary socket access for the hardware-backed tunnel process.
  - Added a runtime `android.permission.POST_NOTIFICATIONS` permission request flow in `MainActivity` for devices running API 33+ (Android 13+), ensuring permission is requested before notifications are shown.
  - Documented and verified graceful handling of denied notification cases inside `TunnelService` to guarantee the service still boots and functions as expected.
  - Audited all modules' manifests, verifying that required foreground service permissions (`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`) are declared properly.
  - Implemented unit and integration tests confirming the merged manifest contains the declared permissions and that the `POST_NOTIFICATIONS` runtime prompt is requested on API 33+ and correctly omitted on API 32 and below.
- **Phase 6.4: Path Semantics & Replay Hardening Remediation**:
  - Modified `PolicyEngineImpl` to explicitly reject any non-file-scheme paths (such as `content://` URIs or other schemes containing `://`) with a clear, named rejection reason to avoid silent mis-canonicalization.
  - Refactored `AuthorizationResult.Allowed` to carry the verified canonical path of the request resolved by the policy engine.
  - Resolved the check-then-use (TOCTOU) vulnerability in `McpExecutor` by extracting the authorized canonical path from the authorization result and threading that exact path to all subsequent `FileSystemReader` operations.
  - Hardened `ReplayProtectionImpl` by migrating `seenNonces` to a synchronized timestamp-backed hash map and implementing opportunistic eviction of nonces older than `windowSizeMs` during each validation request to prevent unbounded memory growth.
  - Implemented unit and integration tests verifying clean content URI rejection, symlink TOCTOU protection under target swapping, and bounded nonce memory growth under sustained load.
- **Phase 6.3: Tunnel Lifecycle Truthfulness Remediation**:
  - Eliminated the silent fallback logic in production that falsely claimed the tunnel was running when the real binary was missing.
  - Implemented real binary verification on `libcloudflared.so` using ELF magic number header checks (`0x7F 'E' 'L' 'F'`), returning a robust `TunnelState.UNAVAILABLE` when the files are placeholders.
  - Refactored `TunnelManager` and `TunnelManagerImpl` to expose `stateFlow: StateFlow<TunnelState>` and accept an injectable `TunnelEnvironment` test seam to dynamically toggle binary availability under test scenarios.
  - Replaced the vulnerable WorkManager countdown delay loop (which was subject to the 10-minute ceiling limit) with a lifecycle-bound coroutine countdown in `TunnelService`, enabling correct session expiration for long-lived TTLs.
  - Modified the Foreground Notification inside `TunnelService` to reactively update its content live based on `stateFlow` updates, showing "Tunnel Active", "Tunnel Stopped", or "Tunnel Unavailable".
  - Created JUnit/Robolectric test cases verifying `Unavailable` behavior, live notifications, and long-TTL virtual clock countdowns.
- **Phase 6.2: Session State Correctness Remediation**:
  - Fixed `SessionManagerImpl.startSession()` so that every valid transition into the `ACTIVE` state resets `ttlSeconds` to the default configured value (300 seconds), correcting the bug where a new session started after a prior `EXPIRED` session would inherit a stale or zeroed TTL.
  - Resolved `UserGesture.NotificationAction` dead-end gesture type by changing the `SessionManager` public interface to add a legitimate state transition method `extendSession()`. This allows `NotificationAction` to extend an already `ACTIVE` session's TTL (adding the extension seconds), while keeping starting/activation transitions restricted strictly to `LocalButtonPress`.
  - Added new robust unit tests in `SessionManagerTest` verifying that reactivating an expired session correctly resets the TTL, and that `NotificationAction` successfully extends an active session but is rejected for starting a session.
- **Phase 6.1: Keystore & Attestation Integrity Remediation**:
  - Removed the reflection-based key unwrapping in `TokenIssuerImpl` entirely, allowing signing to occur through the key object interface as exposed by `KeyStoreManager`.
  - Replaced runtime `Build.FINGERPRINT` and `Build.HARDWARE` sniffing with an explicit, injectable `KeyStoreEnvironment` test seam parameter in `KeyStoreManager`.
  - Rewrote `NonExportablePrivateKey` to execute signatures using an encapsulated lambda/closure, eliminating the private `delegate` key field to prevent reflective extraction of private key materials.
  - Added JUnit/Robolectric test cases in `KeyStoreAndFingerprintTest` verifying that reflective unwrap attempts fail to extract usable keys and that the production path behaves independently of build fields.

## [1.4.0] - 2026-07-07

### Added
- **Phase 5: Policy Engine and Raw Filesystem Authorization**:
  - Implemented `PolicyEngine` and `PolicyEngineImpl` in `:core:policy` validating user request paths against capability-signed roots and checking operation granularity.
  - Implemented complete file-system canonicalization using `java.io.File.canonicalPath` to resolve path traversals (`../`) and symbolic links.
  - Added Unicode normalization (using `java.text.Normalizer` Form NFC) to compare requested paths and allowed roots across different decomposition standards (NFC vs. NFD).
  - Implemented a complete JUnit test suite (`PolicyEngineTest`) verifying path traversal escapes, symlink sandbox bypasses, Unicode Normalization equivalence, operation granularity constraints, and session pre-check validation rules.

## [1.3.0] - 2026-07-07

### Added
- **Phase 4: Capability Token Issuer and Replay Protection**:
  - Implemented `TokenIssuer` and `TokenIssuerImpl` in `:core:keystore` generating JWT-like signed tokens (Base64 URL-safe JSON structures appended with elliptic curve ECDSA SHA256 signatures).
  - Added key-unwrapping fallback using reflection to cleanly extract JVM-fallback keys during non-exportable key execution under Robolectric.
  - Implemented `ReplayProtection` and `ReplayProtectionImpl` in `:core:session` ensuring thread-safe in-memory sliding-window checks (including configurable boundary limits) and strict state pre-check verification.
  - Created a robust JUnit/Robolectric test suite `ReplayAndTokenProtectionTest` verifying token tampering rejection, replay detection, timestamp boundary validations, and correct ordering of state-pre-checks.

## [1.2.0] - 2026-07-07

### Added
- **Phase 3: Tunnel Lifecycle Manager**:
  - Implemented `TunnelService` in `:core:tunnel` as a Foreground Service using persistent status notifications compliant with Android 14+ (`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`).
  - Added native packaging structure for `cloudflared` under `app/src/main/jniLibs/armeabi-v7a/` and `arm64-v8a/` paths.
  - Implemented `TunnelManager` and `TunnelManagerImpl` to manage background `cloudflared` binary lifecycle with fallback command executing on non-matching host environments.
  - Integrated WorkManager `TtlCheckWorker` to poll session TTL, automatically expiring sessions and tearing down processes upon TTL reaching 0.
  - Written comprehensive Robolectric test suite `TunnelLifecycleManagerTest` validating correct process execution and termination, TTL expiration flows, and process restart/low-memory resets.

## [1.1.0] - 2026-07-07

### Added
- **Phase 2: Session Manager**:
  - Implemented the core session state machine (`SessionState`) in `:core:session` supporting INACTIVE, ACTIVE, EXPIRED, and REVOKED states.
  - Implemented the `SessionManager` and `SessionManagerImpl` with high-integrity, thread-safe state transition logic.
  - Defined the `UserGesture` sealed class restricting activation to local gesture checks (`LocalButtonPress`).
  - Added full transition unit test coverage (100% of legal/illegal state transitions, terminal states) in `SessionManagerTest`.
  - Added robust reflection-rejection and Unsafe-allocation bypass tests to guarantee remote trigger protection.

## [1.0.0] - 2026-07-06

### Added
- Standard Gradle module architecture (`:app`, `:core:keystore`, `:core:session`, `:core:tunnel`, `:core:policy`, `:core:filesystem`, `:core:mcp`, `:core:audit`).
- Automated testing infrastructure using JUnit 5, Mockk, and Roborazzi.
- Intentionally failing placeholder test for CI pipeline verification.
- GitHub Actions CI pipeline configuration (`.github/workflows/build.yml`).

### Fixed
- Standardized namespaces and package naming conventions to `com.inscopelabs.abxmcp`.
- Aligned `AndroidManifest.xml` with renamed `MainActivity` package.
- Removed hardcoded debug keystore credentials from `build.gradle.kts`.
- Configured Secrets Gradle Plugin with `.env.abxmcp` defaults.
- Added placeholder `google-services.json` to configure Firebase correctly.
- Added project repository metadata: `LICENSE`, `CONTRIBUTING.md`, and issue templates.
