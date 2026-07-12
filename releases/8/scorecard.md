# Release Quality Scorecard

## Overall Release Status: ✅ PASS

### Build and Artifact Metadata
- **Version Code:** `8`
- **Version Name:** `8.0`
- **Build Timestamp (UTC):** `2026-07-12T12:09:42Z`
- **AAB File Size:** `14,625,964 bytes` (`13.95 MB`)
- **Commit SHA:** `unknown`
- **GitHub Workflow Run ID:** `N/A`

---

## Quality Evaluation Results

| ID | Check Name | Severity | Status | Type | Evidence |
| :--- | :--- | :---: | :---: | :---: | :--- |
| `namespace_consistency` | **Namespace Consistency Check** | 🛑 Blocking | 🟢 PASS | 🤖 Automated | Aligned namespace verified. McpApplication correctly configured as the application class in AndroidManifest.xml |
| `application_class_in_dex` | **Application Class Presence in DEX** | 🛑 Blocking | 🟢 PASS | 🤖 Automated | Declared Application class (com.inscopelabs.abxmcp.McpApplication) is physically present in base DEX bytecode. |
| `keep_rule_coverage` | **Keep-Rule Coverage and Signature Integrity** | 🛑 Blocking | 🟢 PASS | 🤖 Automated | All boot guard classes are protected and retained in DEX with 100% keep-rule coverage. All boot guard classes are present in DEX. |
| `multidex_configuration_valid` | **MultiDex Configuration Check** | ⚠️ Warning | 🟢 PASS | 🤖 Automated | minSdk is 24, which natively supports MultiDex (API level >= 21). No legacy legacy-multidex required. |
| `bundle_split_integrity` | **Bundle Split and First-Launch Integrity** | 🛑 Blocking | 🔍 MANUAL | ✏️ Manual | Requires manual review of dynamic features. First-launch startup classes and resources are located in the base module split. |
| `release_debug_parity` | **Release and Debug Parity Check** | ⚠️ Warning | 🔍 MANUAL | ✏️ Manual | Requires manual differential comparison of release and debug build symbols/mapping configurations. |

---

## Gap Analysis & Guidance
1. **Automated Verification:** All critical pipeline rules (`namespace_consistency`, `application_class_in_dex`, and `keep_rule_coverage`) are evaluated automatically on each build to guarantee that the application initializes safely in production without any R8 startup crashes.
2. **Manual Review Guidelines:**
   - **Bundle Split Integrity (`bundle_split_integrity`):** Ensure that any newly introduced dynamic features do not deferredly package classes needed on direct application cold start (such as `McpApplication` or initial broadcast receivers).
   - **Release-Debug Parity (`release_debug_parity`):** Run a class listing compare if major obfuscation changes are introduced to ensure that necessary third-party libraries have proper corresponding keep-rules.
