# ABX-MCP

Auto Bridge Context Server — a native Android security client implementing
the ABX-MCP architecture (Specification Revision 3.0). Provides a
hardware-backed, session-gated, capability-scoped bridge between an on-device
MCP tool runtime and the local filesystem, with full audit logging.

Built incrementally, phase by phase, against a fixed spec — see
`CHANGELOG.md` for what's landed so far and the phase-by-phase build log for
the full roadmap (0–11).

## Module structure

```
:app                 UI (Jetpack Compose) & orchestration
:core:keystore        Key generation, attestation, non-exportable key material
:core:session          Session state machine, local-gesture-gated activation
:core:tunnel           Foreground service, cloudflared process lifecycle
:core:policy           Allowlist enforcement, path/operation authorization
:core:filesystem       Raw IO + SAF-backed file access
:core:mcp              MCP tool execution handlers
:core:audit            Append-only, hash-chained, signable audit log
```

Manual dependency injection throughout (no Dagger/Hilt) — module boundaries
are enforced by construction, not by a DI graph.

## Building

This project is developed inside Google AI Studio and has **no committed
Gradle wrapper** — there's no local shell in this workflow to generate one.
CI (`.github/workflows/build.yml`) uses `gradle/actions/setup-gradle` to
fetch Gradle directly rather than relying on `./gradlew`.

If you're opening this in Android Studio instead:

1. Open Android Studio, **Open**, select this directory, let it sync.
2. Create a `.env` file in the project root and set `GEMINI_API_KEY` (see
   `.env.example`). Only needed if you're exercising the (currently unused)
   Firebase AI dependency — core ABX-MCP functionality doesn't require it.
3. Run on an emulator or physical device.

**Note on emulators:** `core/keystore`'s `KeyStoreManager` currently treats
any device reporting `Build.HARDWARE` of `goldfish` or `ranchu` (i.e. *any*
Android emulator, not just Robolectric) as a non-secure environment and
falls back to an in-memory mock keystore. This means emulator runs — AI
Studio's included emulator or otherwise — will not exercise real
`AndroidKeyStore` behavior. Verified functionality (hardware-backed key
generation, attestation, TEE status) requires a physical device via the Play
Console internal test track. This is a known issue to be fixed before it
causes confusion in later phases.

## Signing

- **Debug builds** use `debugConfig`, driven by `DEBUG_STORE_PASSWORD` /
  `DEBUG_KEY_ALIAS` / `DEBUG_KEY_PASSWORD` env vars, falling back to Android's
  standard public debug-keystore defaults if unset. No custom secrets are
  hardcoded.
- **Release builds** use `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD`
  env vars, expected to point at a real upload keystore. Not committed to
  the repo.

## Testing

- Unit tests (JUnit 5 / Mockk / Robolectric) run via `gradle test`, wired
  into CI on every push.
- `connectedAndroidTest` is **intentionally not run in CI** — GitHub-hosted
  runners have no attached device, and this project has no CI emulator step.
  Instrumented behavior is currently verified manually via AI Studio's
  emulator (with the caveat above) and, where hardware-backed behavior
  actually matters, a real device through the test track.

## Firebase

`google-services.json` in this repo is a **placeholder** (fake project ID,
fake API key). Nothing in the app currently calls Firebase at runtime, so
this doesn't affect current functionality — but it will need a real Firebase
project wired in before any phase that actually exercises `firebase.ai` or
App Check.
