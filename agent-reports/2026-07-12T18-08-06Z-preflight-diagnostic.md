# Agent Process Report: Preflight Diagnostic & Standing Instructions

## 1. Request Details
* **Task:** Minimal Preflight Diagnostic + AGENTS.md Standing Instructions
* **Date:** 2026-07-12
* **Requested By:** User

## 2. Changes Made
We created the following files to setup the diagnostic workflow and persistent standing instructions:

### A. Minimal Preflight Diagnostic Workflow
* **File Path:** `.github/workflows/preflight-diagnostic.yml` (New File)
* **Summary:** A minimal GitHub Actions workflow triggered via `workflow_dispatch` on the `ENV_ABX_MCP` environment to inspect and output secret presence, Android SDK path, and log file metadata to a job summary and commit/push it. 
* **Dependency Check:** This workflow contains zero calls to Gradle, bundletool, or external shell scripts under `tools/`.

### B. Standing Instructions (AGENTS.md)
* **File Path:** `AGENTS.md` (New File at Root)
* **Summary:** Created instructions at the project root which the AI Studio platform automatically reads and injects into system instructions. Establishes rules for:
  1. **GitHub Drift Protection:** Instructions to fetch and compare critical files from raw GitHub source before modifying to protect against stale overwrite.
  2. **Mandatory Process Report:** Instructions to write task reports to `agent-reports/` to ensure a permanent historical trail of task completions.
  3. **Scope Discipline:** Standard limits to prevent over-engineering.

### C. Verification of Untouched Files
* `.github/workflows/preflight-release.yml` remains completely untouched (no modifications).
* `tools/verify-release-artifact.sh` remains completely untouched (no modifications).
* `tools/keep-rule-coverage.sh` remains completely untouched (no modifications).

---

## 3. Environment & Execution Trace

To address the user's questions and verify our runtime capabilities, the following commands were executed:

1. **`git remote -v && git status`**
   * **Result:** `fatal: not a git repository (or any of the parent directories): .git`
   * **Inference:** The container environment operates without a local `.git` subdirectory. All git tracking, staging, committing, and pushing is performed asynchronously by the AI Studio platform's backend synchronization engine.

2. **`find . -name ".git" -type d`**
   * **Result:** Empty.
   * **Inference:** Confirms that no local Git repository database is present within the workspace folder.

3. **`view_file` on `/.gitignore`**
   * **Result:** Viewed lines 1-24. No ignore rules matching `agent-reports` are present.

---

## 4. Assumptions & Platform Discovery
* **Standing Instructions Recognition:** The AI Studio platform's system instructions explicitly declare: *"The contents of AGENTS.md and GEMINI.md at the project root are automatically injected by the system into your system instructions."* Therefore, `AGENTS.md` at the root `/AGENTS.md` is functionally recognized and read by the system.
* **Commit/Push Behavior:** Since there is no local `.git` repository, the agent cannot manually run `git commit` or `git push` inside the container. Instead, writing files directly to the workspace serves as the staging mechanism; the platform automatically commits and pushes all created/modified workspace files upon turn completion.

---

## 5. Potential Failures/Unverified Items
* Since we do not have an automated GitHub actions runner within our direct container environment, we are unable to trigger or execute the workflow at `.github/workflows/preflight-diagnostic.yml` on-demand; the workflow must be manually triggered via the GitHub web UI or API by dispatch.
