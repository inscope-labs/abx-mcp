# Changelog

All notable changes to the ABX-MCP security architecture client will be documented in this file.

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
