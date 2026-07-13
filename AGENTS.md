# Standing Instructions for AI Studio Build Agent — abx-mcp

## 1. GitHub Drift Protection

This workspace has no local git pull/fetch capability and cannot verify
it is in sync with GitHub `main`. Treat GitHub as the sole source of
truth for the following paths. Before editing any of them, fetch the
current live content from:

https://raw.githubusercontent.com/inscope-labs/inscope-labs/abx-mcp/main/<path>

and compare it against your local working copy. If they differ, report
the diff and pause for confirmation before overwriting.

Protected paths:
- .github/workflows/*.yml
- app/proguard-rules.pro
- app/src/main/AndroidManifest.xml
- app/build.gradle.kts

Never assume the local workspace reflects the current state of `main`.

## 2. Mandatory Process Report on Every Task

This environment provides no way to copy, save, or download your
responses. You MUST record a report for every task you complete, saved
as an actual file in the repository (not just a chat response),
committed and pushed:

Path: agent-reports/<UTC-ISO-timestamp>-<short-task-slug>.md

The report must include:
- What was asked.
- What you actually changed (files touched, with a diff or summary).
- Any commands you ran and their results.
- Any assumptions you made.
- Any errors, partial failures, or things you were unable to verify.

Do not overwrite previous reports — each task gets its own timestamped
file. This folder must NOT be gitignored; it must be pushed to GitHub
so it can be read outside this environment.

## 3. Scope Discipline

Only make the changes explicitly requested in the current prompt. Do
not regenerate, reformat, or "improve" files beyond what was asked,
even if it seems in the spirit of the request.

## 4. version.properties Is CI-Owned — Never Write To It

version.properties is exclusively written by the diagnostic workflow's
automated versionCode bump logic (.github/workflows/preflight-diagnostic.yml).
It must never be edited by you, under any circumstances, even
incidentally as part of a broader commit that happens to include it.

If your workspace snapshot contains a copy of version.properties that
differs from the current value on GitHub `main`, do not include it in
any commit you push — explicitly exclude it, and note the discrepancy
in your agent-reports/ entry for that task instead. Never resolve a
difference by overwriting the GitHub value with your local one.

