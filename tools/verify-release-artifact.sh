#!/bin/bash
# Permanent Production Infrastructure: Release Artifact Verification Script
# This script is executed prior to production submission to catch R8 keep-rule gaps,
# manifest mismatches, or packaging regressions in the generated Android App Bundle (AAB).
#
# Usage: ./tools/verify-release-artifact.sh <path_to_aab>
#
# Requirements:
# - java, aapt2, dexdump, unzip must be installed and in the PATH (or ANDROID_SDK_ROOT configured).

set -euo pipefail

# Helper for logging
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

AAB_DIR=$(dirname "$AAB_PATH")
REPORT_PATH="$AAB_DIR/verify-report.json"

log_info "Starting verification for AAB: $AAB_PATH"

# Setup temp directory
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

# Locate bundletool
BUNDLETOOL="bundletool.jar"
if [ ! -f "$BUNDLETOOL" ]; then
    if [ -f "/tmp/bundletool.jar" ]; then
        BUNDLETOOL="/tmp/bundletool.jar"
    else
        log_info "bundletool.jar not found in current directory or /tmp/. Attempting to locate..."
        FOUND_BT=$(find . -name "bundletool*.jar" | head -n 1)
        if [ -n "$FOUND_BT" ]; then
            BUNDLETOOL="$FOUND_BT"
        else
            log_fail "bundletool.jar could not be located. Please place it in the working directory."
            exit 1
        fi
    fi
fi
log_info "Using bundletool: $BUNDLETOOL"

# Locate Android SDK build tools
AAPT2="aapt2"
DEXDUMP="dexdump"

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    # Try to find aapt2 and dexdump in build-tools
    FOUND_AAPT2=$(find "$ANDROID_SDK_ROOT" -name "aapt2" -type f | head -n 1 || true)
    FOUND_DEXDUMP=$(find "$ANDROID_SDK_ROOT" -name "dexdump" -type f | head -n 1 || true)
    if [ -n "$FOUND_AAPT2" ]; then AAPT2="$FOUND_AAPT2"; fi
    if [ -n "$FOUND_DEXDUMP" ]; then DEXDUMP="$FOUND_DEXDUMP"; fi
fi

log_info "Using aapt2: $AAPT2"
log_info "Using dexdump: $DEXDUMP"

# Initialize JSON report values
CHECK_BUNDLETOOL="FAIL"
CHECK_MANIFEST_APP_CLASS="FAIL"
CHECK_DEX_APP_CLASS="FAIL"
CHECK_MAPPING_FILE="SKIPPED"
CHECK_AAPT_RULES="SKIPPED"
CHECK_PROGUARD_KEEPS="FAIL"

DETAIL_MANIFEST=""
DETAIL_DEX=""
DETAIL_MAPPING=""
DETAIL_AAPT_RULES=""
DETAIL_PROGUARD_KEEPS=""
COVERAGE_JSON=""

write_json_report() {
    local status="$1"
    local error_msg="${2:-""}"
    local coverage_data="[]"
    if [ -n "${COVERAGE_JSON:-}" ] && [ -f "$COVERAGE_JSON" ]; then
        coverage_data=$(cat "$COVERAGE_JSON")
    fi

    cat <<EOF > "$REPORT_PATH"
{
  "status": "$status",
  "error": "$error_msg",
  "checks": {
    "bundletool_conversion": "$CHECK_BUNDLETOOL",
    "manifest_application_class": "$CHECK_MANIFEST_APP_CLASS",
    "dex_application_class": "$CHECK_DEX_APP_CLASS",
    "r8_mapping_verification": "$CHECK_MAPPING_FILE",
    "aapt_rules_verification": "$CHECK_AAPT_RULES",
    "proguard_keep_rules": "$CHECK_PROGUARD_KEEPS"
  },
  "details": {
    "manifest": "$DETAIL_MANIFEST",
    "dex": "$DETAIL_DEX",
    "mapping": "$DETAIL_MAPPING",
    "aapt_rules": "$DETAIL_AAPT_RULES",
    "proguard_keeps": "$DETAIL_PROGUARD_KEEPS"
  },
  "coverage": $coverage_data
}
EOF
    log_info "Report written to: $REPORT_PATH"
}

# 1. Convert AAB to APKs via bundletool
log_info "Running Check 1: Converting AAB to universal APK..."
APKS_OUT="$TEMP_DIR/universal.apks"
if java -jar "$BUNDLETOOL" build-apks --bundle="$AAB_PATH" --output="$APKS_OUT" --mode=universal 2>/dev/null; then
    CHECK_BUNDLETOOL="PASS"
    log_pass "Converted AAB to universal APKS successfully."
else
    write_json_report "FAIL" "Failed to convert AAB to universal APKs via bundletool."
    log_fail "Failed to convert AAB to universal APKs."
    exit 1
fi

# Extract the universal APK
log_info "Extracting universal.apk..."
unzip -q -d "$TEMP_DIR/extracted_apks" "$APKS_OUT"
APK_PATH="$TEMP_DIR/extracted_apks/universal.apk"
if [ ! -f "$APK_PATH" ]; then
    write_json_report "FAIL" "universal.apk not found inside converted APKS container."
    log_fail "Could not find universal.apk after extraction."
    exit 1
fi

# 2. Dump and inspect manifest for com.inscopelabs.abxmcp.McpApplication
log_info "Running Check 2: Inspecting manifest for Application class declaration..."
MANIFEST_XML="$TEMP_DIR/AndroidManifest.xml"
if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK_PATH" > "$MANIFEST_XML" 2>/dev/null; then
    # Look for application tag and its android:name attribute
    # We check if com.inscopelabs.abxmcp.McpApplication is declared
    if grep -A 5 "E: application" "$MANIFEST_XML" | grep -q "com.inscopelabs.abxmcp.McpApplication"; then
        CHECK_MANIFEST_APP_CLASS="PASS"
        DETAIL_MANIFEST="McpApplication correctly configured as the application class in AndroidManifest.xml"
        log_pass "Manifest declares com.inscopelabs.abxmcp.McpApplication"
    else
        DETAIL_MANIFEST="Manifest does not declare com.inscopelabs.abxmcp.McpApplication as the application class!"
        write_json_report "FAIL" "$DETAIL_MANIFEST"
        log_fail "$DETAIL_MANIFEST"
        exit 2
    fi
else
    write_json_report "FAIL" "Failed to dump AndroidManifest.xml using aapt2."
    log_fail "Failed to dump manifest."
    exit 1
fi

# 3. Inspect DEX contents for McpApplication class presence
log_info "Running Check 3: Checking DEX for presence of McpApplication..."
if "$DEXDUMP" "$APK_PATH" | grep -q "Class descriptor  : 'Lcom/inscopelabs/abxmcp/McpApplication;'"; then
    CHECK_DEX_APP_CLASS="PASS"
    DETAIL_DEX="McpApplication class definition is present in the DEX payload."
    log_pass "McpApplication class exists in the DEX payload."
else
    DETAIL_DEX="McpApplication class descriptor is missing from the DEX! R8 may have stripped it."
    write_json_report "FAIL" "$DETAIL_DEX"
    log_fail "$DETAIL_DEX"
    exit 3
fi

# 4. Check mapping.txt if present
log_info "Running Check 4: Verifying mapping.txt rules..."
MAPPING_FILE="app/build/outputs/mapping/release/mapping.txt"
ACTUAL_MAPPING_FILE=$(find app/build -name "mapping.txt" | head -n 1 || true)
if [ -z "$ACTUAL_MAPPING_FILE" ] && [ -f "$MAPPING_FILE" ]; then
    ACTUAL_MAPPING_FILE="$MAPPING_FILE"
fi

if [ -f "$ACTUAL_MAPPING_FILE" ]; then
    if grep -q "com.inscopelabs.abxmcp.McpApplication ->" "$ACTUAL_MAPPING_FILE"; then
        CHECK_MAPPING_FILE="PASS"
        DETAIL_MAPPING="McpApplication is correctly mapped/kept in mapping.txt"
        log_pass "McpApplication is referenced in mapping.txt"
    else
        CHECK_MAPPING_FILE="FAIL"
        DETAIL_MAPPING="McpApplication is missing from mapping.txt!"
        write_json_report "FAIL" "$DETAIL_MAPPING"
        log_fail "$DETAIL_MAPPING"
        exit 4
    fi
else
    CHECK_MAPPING_FILE="SKIPPED"
    DETAIL_MAPPING="No mapping.txt found (minification might be disabled or not run yet)."
    log_info "No mapping.txt found. Skipping mapping check."
fi

# 5. Check aapt_rules.txt if present
log_info "Running Check 5: Verifying aapt_rules.txt..."
AAPT_RULES="app/build/intermediates/aapt_proguard_file/release/aapt_rules.txt"
ACTUAL_AAPT_RULES=$(find app/build -name "aapt_rules.txt" -o -name "*aapt*rules*" | head -n 1 || true)
if [ -z "$ACTUAL_AAPT_RULES" ] && [ -f "$AAPT_RULES" ]; then
    ACTUAL_AAPT_RULES="$AAPT_RULES"
fi

if [ -f "$ACTUAL_AAPT_RULES" ]; then
    if grep -q "com.inscopelabs.abxmcp.McpApplication" "$ACTUAL_AAPT_RULES"; then
        CHECK_AAPT_RULES="PASS"
        DETAIL_AAPT_RULES="McpApplication is listed in auto-generated aapt_rules.txt"
        log_pass "McpApplication is present in aapt_rules.txt"
    else
        CHECK_AAPT_RULES="FAIL"
        DETAIL_AAPT_RULES="McpApplication is missing from aapt_rules.txt! Possible namespace mismatch."
        write_json_report "FAIL" "$DETAIL_AAPT_RULES"
        log_fail "$DETAIL_AAPT_RULES"
        exit 5
    fi
else
    CHECK_AAPT_RULES="SKIPPED"
    DETAIL_AAPT_RULES="No aapt_rules.txt found. Skipping."
    log_info "No aapt_rules.txt found. Skipping."
fi

# 6. Verify presence of other classes protected by explicit keep rules
log_info "Running Check 6: Verifying other protected classes (com.inscopelabs.abxmcp.boot.*)..."
# We've added BootGuard, BootRoute, and RecoveryActivity. Let's make sure they are present in DEX
BOOT_CLASSES_FOUND=true
for CLS in "BootGuard" "BootRoute" "RecoveryActivity"; do
    if "$DEXDUMP" "$APK_PATH" | grep -q "Class descriptor  : 'Lcom/inscopelabs/abxmcp/boot/$CLS;'"; then
        log_pass "Protected class: com.inscopelabs.abxmcp.boot.$CLS is present in DEX."
    else
        log_fail "Protected class: com.inscopelabs.abxmcp.boot.$CLS is MISSING from DEX!"
        BOOT_CLASSES_FOUND=false
    fi
done

if [ "$BOOT_CLASSES_FOUND" = true ]; then
    CHECK_PROGUARD_KEEPS="PASS"
    DETAIL_PROGUARD_KEEPS="All boot guard classes are present in DEX."
else
    CHECK_PROGUARD_KEEPS="FAIL"
    DETAIL_PROGUARD_KEEPS="One or more boot guard classes were stripped from DEX!"
    write_json_report "FAIL" "$DETAIL_PROGUARD_KEEPS"
    exit 6
fi

# 7. Run companion keep-rule coverage report
log_info "Running Check 7: Gathering comprehensive Keep-Rule Coverage Report..."
COVERAGE_JSON="$TEMP_DIR/coverage.json"
if ./tools/keep-rule-coverage.sh "$APK_PATH" "$ACTUAL_MAPPING_FILE" "$ACTUAL_AAPT_RULES" "$COVERAGE_JSON"; then
    log_pass "Keep-rule coverage generated successfully."
else
    write_json_report "FAIL" "Keep-rule coverage analysis failed or detected a manifest mismatch (v7 signature)."
    log_fail "Keep-rule coverage check failed."
    exit 7
fi

# Complete success write
write_json_report "PASS"
log_pass "All release artifact verification checks passed successfully!"
exit 0
