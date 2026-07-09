# Google Play Store Submission Readiness Documentation

This document prepares **ABC Server** for successful Google Play Store submission, ensuring compliance with all target SDK policies, permission justifications, data safety disclosures, privacy guidelines, and store listing assets.

---

## 1. Target SDK Compliance Verification

*   **Configured Target SDK:** `36` (Android 16)
*   **Google Play Submission Policy:** Google Play requires all new apps to target at least Android 15 (API level 35) or higher.
*   **Compliance Status:** **Fully Compliant**. By targeting API level 36, ABC Server ensures compatibility with the latest Android security models, runtime behavior changes, and complies with all Play Store submission deadlines for 2025 and 2026.

---

## 2. Play Data Safety Declaration

This declaration lists all data types collected or processed by ABC Server, their transit/at-rest state, and deletion behaviors.

### Data Types Collected & Processed
1.  **Files and Documents (Read-Only Access):**
    *   **Description:** The app accesses local files and directory trees as authorized by the user's cryptographic Capability Token.
    *   **Purpose:** App functionality. Transmitted securely through the local loopback or local Cloudflare tunnel to the user-authorized Model Context Protocol (MCP) host.
    *   **Sharing:** **Never shared with third parties.**
2.  **Device and Security Identifiers (Hardware Public Key):**
    *   **Description:** Generates unique, non-exportable cryptographic key pairs inside the device's hardware-backed KeyStore (TEE/StrongBox) for hardware attestation and session-gate signatures.
    *   **Purpose:** Session security, integrity checks, and token authentication.
    *   **Sharing:** **Never shared with third parties.**
3.  **App Logs & Diagnostics (Local Audit Trail):**
    *   **Description:** Writes an append-only, cryptographically signed hash-chain of all security policy rejections, unauthorized access attempts, and session status failures.
    *   **Purpose:** User auditability and compliance tracking.
    *   **Sharing:** **Never shared with third parties. Stored strictly on-device.**

### Encryption and Security
*   **In Transit:** All external traffic is routed through an end-to-end encrypted `cloudflared` tunnel, secured using Transport Layer Security (TLS 1.3). No plain-text data leaves the device.
*   **At Rest:** All audit trails are stored strictly inside a sandboxed JSON Lines (`.jsonl`) file, and active session states are maintained in-memory, all protected inside the application's private files directory by Android's File-Based Encryption (FBE). Keys are protected inside the secure enclave (TEE/StrongBox) and are non-exportable.

### Data Deletion
*   **Uninstall Purge:** All keys, databases, session state, cached configurations, and audit trails are completely and automatically destroyed by the Android operating system when the user uninstalls the application.
*   **In-App Purge:** Users can manually delete their cryptographic enrollment keys from the Enrollment Screen at any time, instantly preventing future capability token signature verification and effectively invalidating all issued capabilities.

---

## 3. Hosted Privacy Policy Draft

**URL:** `https://privacy.inscopelabs.com/abc-server` (or customer self-hosted landing page)

### Privacy Policy for ABC Server
**Effective Date:** July 8, 2026

#### Introduction
ABC Server ("we", "us", or "our") is a developer security tool designed to act as a secure, session-gated bridge between your local Android filesystem and Model Context Protocol (MCP) clients. Your privacy is paramount. ABC Server is designed under a strict **local-first and zero-knowledge** architecture.

#### Data We Access (On-Device Only)
1.  **Local Filesystem:** We only access the directories you explicitly approve via your cryptographically signed Capability Tokens.
2.  **Hardware Enclave (Android KeyStore):** We utilize the hardware-backed Trusted Execution Environment (TEE) or StrongBox to generate non-exportable signature keys. These keys never leave your device.
3.  **Local Audit Trail:** A signed, append-only ledger of security policy rejections and unauthorized access attempts is stored strictly in a local file inside the app's secure sandbox directory.

#### Data Transit
ABC Server does not run a central cloud service, nor does it collect, harvest, or aggregate your files or keys. Any data transmitted to an external MCP client is routed through a secure, end-to-end encrypted tunnel (`cloudflared`) managed directly under your account credentials. We have no visibility into the payload, metadata, or contents of these tunnels.

#### User Consent and Security Controls
*   **Explicit Session Gating:** ABC Server cannot process any request unless a physical user-gesture (e.g., button press) explicitly starts or extends the active session.
*   **Scope Invalidation:** You can instantly delete your enrollment keys with a single tap in the app, preventing verification of any issued capability tokens.

#### Changes to This Policy
We may update this privacy policy from time to time to reflect security improvements or specification updates.

---

## 4. Restricted Permission Justifications

These arguments are prepared for the Google Play Console's declaration forms.

### Permission: `FOREGROUND_SERVICE_SPECIAL_USE`

*   **Detailed Description of Core Feature:**
    ABC Server acts as a persistent local Model Context Protocol (MCP) bridge. It manages a secure background socket connection (`cloudflared` tunnel) that pipes verified JSON-RPC queries from developer tools directly to the on-device Policy Engine and FileSystem executor. This bridge must remain active and maintain a stable, non-interrupted network connection during active development sessions.
*   **Why a Foreground Service is Mandatory:**
    If the connection is interrupted or killed by the Android OS's aggressive background battery saver, active AI agent tasks will fail, and session state will get corrupted. A foreground service displaying a persistent, non-dismissible notification is strictly required to inform the user that their secure hardware-backed bridge is actively running, and to prevent the OS from killing the tunnel binary.
*   **User-Gated Safety Architecture (The Core Argument):**
    To ensure complete user safety, this background tunnel is strictly bound to a highly secure, gesture-gated session lifecycle:
    1.  The foreground service *cannot* start unless the user performs a physical button press on the screen.
    2.  The active session is protected by a strict Time-To-Live (TTL) timer (default: 15 minutes).
    3.  Users can manually extend the session via a high-priority Notification Action or immediately kill the entire service and revoke all file access with one tap.

---

## 5. Store Listing Assets

### Text Metadata
*   **Application Name:** ABC Server
*   **Short Description (Max 80 chars):** Secure, session-gated developer bridge for Model Context Protocol (MCP).
*   **Long Description (Max 4000 chars):**
    ABC Server is a native, hardware-hardened Model Context Protocol (MCP) security bridge for developers and enterprise administrators. It acts as an isolated, session-gated conduit enabling remote LLM agents and workspace tools to query authorized directories on your Android device safely.

    Designed with a zero-trust, local-first architecture, ABC Server replaces insecure generic listeners with a robust, cryptographically verified pipeline:

    ■ HARDWARE-BACKED KEY MANAGEMENT
    Utilizes your device's Trusted Execution Environment (TEE) or StrongBox to generate non-exportable EC key pairs. Cryptographic hardware attestation ensures absolute key origin integrity.

    ■ SESSION-GATED CONTROL
    Total lifecycle sovereignty. File access is strictly locked unless explicitly authorized by a local, physical gesture (button press). Session durations are limited by a secure background TTL countdown.

    ■ CAPABILITY-SCOPED POLICIES
    Every query is checked against cryptographically signed Capability Tokens specifying allowed directory roots and granular operations. Any attempt to traverse paths outside the scope is instantly rejected.

    ■ TOCTOU SYMLINK & SCHEME PROTECTION
    Engineered with strict Time-of-Check to Time-of-Use defenses. Normalizes and validates canonical paths before execution to prevent path traversal exploits, directory escaping, symlink-swapping attacks, or content:// URI leaks.

    ■ HARDENED REPLAY DEFENSES
    Protected by synchronized timestamp-based nonce tracking, utilizing a bounded-memory concurrent cache to filter duplicate requests and defend against session replay attacks.

    ■ AUDIT LOGS
    Keeps an append-only, signed, hash-chained local audit trail of all security policy rejections and unauthorized access attempts, guaranteeing total, immutable user-auditability over what security violations have occurred.

    Bridge the power of the Model Context Protocol to your local environment without compromising on core device security.

### Listing Sizing Reference Table
| Asset | Sizing Requirement | Format |
| :--- | :--- | :--- |
| **App Icon** | 512 x 512 px, 32-bit PNG, max 1MB | PNG |
| **Feature Graphic** | 1024 x 500 px, JPEG or 24-bit PNG, max 1MB | PNG / JPEG |
| **Phone Screenshots** | Min 320px, Max 3840px (16:9 or 9:16) | PNG / JPEG (At least 2) |
| **7-inch Tablet** | Min 320px, Max 3840px (16:9 or 9:16) | PNG / JPEG (At least 1) |
| **10-inch Tablet** | Min 320px, Max 3840px (16:9 or 9:16) | PNG / JPEG (At least 1) |

---

## 6. Pre-Launch Verification Status

All regression tests and release-specific sanitization checks are passing successfully.
*   **Shrinking/Obfuscation Verification:** Passing. Core model deserialization, SQLite storage, and cryptography remain functional under full Proguard minification.
*   **Release-Only Redaction:** Verified. Path names, PolicyEngine exceptions, and authorization rejections are genericized into non-leaking "Access denied" errors under release builds, with full debug output strictly restricted to local tests.
