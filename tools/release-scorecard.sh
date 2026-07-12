#!/bin/bash
# Permanent Production Infrastructure: Release Scorecard Generator
# This script executes the release quality playbook, evaluates all taxonomy checks,
# gathers build metadata, and generates machine-readable and human-readable scorecards.
#
# Usage: ./tools/release-scorecard.sh <path_to_aab>

set -euo pipefail

log_info() {
    echo -e "\033[1;34m[INFO]\033[0m $1"
}

log_pass() {
    echo -e "\033[1;32m[PASS]\033[0m $1"
}

log_fail() {
    echo -e "\033[1;31m[FAIL]\033[0m $1"
}

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <path_to_aab>"
    exit 1
fi

AAB_PATH="$1"
if [ ! -f "$AAB_PATH" ]; then
    log_fail "AAB file not found at: $AAB_PATH"
    exit 1
fi

log_info "Initializing Scorecard Generator for: $AAB_PATH"

# Setup bundletool path
BUNDLETOOL="/tmp/bundletool.jar"
if [ ! -f "$BUNDLETOOL" ]; then
    # Try current directory
    if [ -f "bundletool.jar" ]; then
        BUNDLETOOL="bundletool.jar"
    else
        log_fail "bundletool.jar not found at /tmp/bundletool.jar or in current directory."
        exit 1
    fi
fi

# 1. Extract metadata from AAB
log_info "Extracting manifest metadata via bundletool..."
MANIFEST_XML=$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB_PATH")

VERSION_CODE=$(echo "$MANIFEST_XML" | grep -oE "android:versionCode=\"[0-9]+\"" | sed -E 's/[^0-9]//g' | head -n 1)
VERSION_NAME=$(echo "$MANIFEST_XML" | grep -oE "android:versionName=\"[^\"]+\"" | sed -E 's/android:versionName="([^"]+)"/\1/' | head -n 1)
MIN_SDK=$(echo "$MANIFEST_XML" | grep -oE "android:minSdkVersion=\"[0-9]+\"" | sed -E 's/[^0-9]//g' | head -n 1)

if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ] || [ -z "$MIN_SDK" ]; then
    log_fail "Failed to extract required metadata (versionCode/versionName/minSdkVersion) from AAB manifest."
    exit 1
fi

log_info "Detected Build Metadata:"
log_info "  - Version Code: $VERSION_CODE"
log_info "  - Version Name: $VERSION_NAME"
log_info "  - Min SDK Version: $MIN_SDK"

# Get other metadata
AAB_SIZE=$(stat -c %s "$AAB_PATH" 2>/dev/null || stat -f %z "$AAB_PATH")
COMMIT_SHA=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
WORKFLOW_RUN_ID="${GITHUB_RUN_ID:-"N/A"}"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# 2. Run release artifact verification script (allow it to fail so we still compile a failing scorecard)
log_info "Running Release Artifact Verification pipeline..."
AAB_DIR=$(dirname "$AAB_PATH")
REPORT_PATH="$AAB_DIR/verify-report.json"

# Remove any stale report
rm -f "$REPORT_PATH"

# Temporarily disable exit on error to capture verification results
set +e
./tools/verify-release-artifact.sh "$AAB_PATH"
VERIFY_EXIT_CODE=$?
set -e

log_info "Verification pipeline exited with status code: $VERIFY_EXIT_CODE"

# 3. Compile Scorecard
log_info "Compiling Release Scorecard..."
python3 tools/compile-scorecard.py \
    "$AAB_PATH" \
    "$REPORT_PATH" \
    "$VERSION_CODE" \
    "$VERSION_NAME" \
    "$MIN_SDK" \
    "$AAB_SIZE" \
    "$COMMIT_SHA" \
    "$WORKFLOW_RUN_ID" \
    "$TIMESTAMP"

SCORECARD_JSON="releases/$VERSION_CODE/scorecard.json"
SCORECARD_MD="releases/$VERSION_CODE/scorecard.md"

if [ ! -f "$SCORECARD_JSON" ] || [ ! -f "$SCORECARD_MD" ]; then
    log_fail "Scorecard generation failed. Outputs were not created."
    exit 1
fi

# 4. Validate scorecard against the JSON Schema
log_info "Validating scorecard against schema..."
if python3 tools/validate-scorecard.py "$SCORECARD_JSON" "tools/scorecard-schema.json"; then
    log_pass "Scorecard matches JSON Schema specification."
else
    log_fail "Scorecard schema validation failed!"
    exit 1
fi

# 5. Extract and evaluate Overall Status
OVERALL_STATUS=$(python3 -c "import json; print(json.load(open('$SCORECARD_JSON'))['metadata']['overall_status'])")

log_info "--------------------------------------------------------"
if [ "$OVERALL_STATUS" = "PASS" ]; then
    log_pass "RELEASE SCORECARD PASSED SUCCESSFULLY! Status: $OVERALL_STATUS"
    exit 0
else
    log_fail "RELEASE SCORECARD FAILED REQUIRED CRITERIA! Status: $OVERALL_STATUS"
    exit 1
fi
