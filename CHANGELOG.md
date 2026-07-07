# Changelog

All notable changes to the ABX-MCP security architecture client will be documented in this file.

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
