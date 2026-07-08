# Changelog

All notable changes to the ABX-MCP security architecture client will be documented in this file.

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
