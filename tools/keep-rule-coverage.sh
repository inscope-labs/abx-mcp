#!/bin/bash
# Permanent Production Infrastructure: Keep-Rule Coverage Analyzer
# This script performs comprehensive R8 keep-rule coverage analysis,
# mapping file verification, and detects manifest-to-rules mismatch signatures.
#
# Usage: ./tools/keep-rule-coverage.sh <apk_path> [mapping_txt_path] [aapt_rules_path] [output_json_path]

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

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <apk_path> [mapping_txt_path] [aapt_rules_path] [output_json_path]"
    exit 1
fi

APK_PATH="$1"
MAPPING_FILE="${2:-""}"
AAPT_RULES="${3:-""}"
OUTPUT_JSON="${4:-""}"

# Setup tool defaults
AAPT2="aapt2"
DEXDUMP="dexdump"

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    FOUND_AAPT2=$(find "$ANDROID_SDK_ROOT" -name "aapt2" -type f | head -n 1 || true)
    FOUND_DEXDUMP=$(find "$ANDROID_SDK_ROOT" -name "dexdump" -type f | head -n 1 || true)
    if [ -n "$FOUND_AAPT2" ]; then AAPT2="$FOUND_AAPT2"; fi
    if [ -n "$FOUND_DEXDUMP" ]; then DEXDUMP="$FOUND_DEXDUMP"; fi
fi

# Find all source classes under com.inscopelabs.abxmcp
find_source_classes() {
    find app core -path "*/src/main/*" -name "*.kt" -o -path "*/src/main/*" -name "*.java" 2>/dev/null | while read -r file; do
        pkg=$(grep -E "^package " "$file" | head -n 1 | sed -E 's/package[[:space:]]+([a-zA-Z0-9_\.]+).*/\1/' || true)
        if [ -z "$pkg" ]; then continue; fi
        
        # Parse class, interface, enum, object declarations
        grep -E '^[[:space:]]*([a-z[:space:]]+)?(class|interface|enum class|object)\s+([a-zA-Z0-9_]+)' "$file" | while read -r line; do
            cls_name=$(echo "$line" | sed -E 's/.*(class|interface|object)[[:space:]]+([a-zA-Z0-9_]+).*/\2/')
            if [ -n "$cls_name" ] && [[ ! "$line" =~ ^[[:space:]]*"//" ]] && [[ ! "$line" =~ ^[[:space:]]*"*" ]]; then
                if [ "$cls_name" != "object" ]; then
                    echo "$pkg.$cls_name"
                fi
            fi
        done
    done | grep "^com\.inscopelabs\.abxmcp" | sort -u
}

# Parse manifest components
get_manifest_classes() {
    local apk="$1"
    local aapt2_bin="$2"
    
    local pkg_name
    pkg_name=$( "$aapt2_bin" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null | grep "package=" | sed -E 's/.*package="([^"]+)".*/\1/' | head -n 1 )
    if [ -z "$pkg_name" ]; then pkg_name="com.inscopelabs.abxmcp"; fi

    "$aapt2_bin" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null | grep "android:name" | sed -E 's/.*"([^"]+)".*/\1/' | while read -r line; do
        if [[ "$line" =~ ^\. ]]; then
            echo "${pkg_name}${line}"
        elif [[ "$line" =~ ^[a-zA-Z0-9_] && ! "$line" =~ \. ]]; then
            echo "${pkg_name}.${line}"
        else
            echo "$line"
        fi
    done | grep "^com\.inscopelabs\.abxmcp" | grep -v "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" | sort -u
}

# Parse proguard-rules.pro keep patterns
get_proguard_patterns() {
    local rules_file="proguard-rules.pro"
    if [ ! -f "$rules_file" ]; then
        return
    fi
    grep -E '^-keep' "$rules_file" | while read -r line; do
        local pattern
        pattern=$(echo "$line" | sed -E 's/^-keep[a-z,]*[[:space:]]+(class|interface|enum)[[:space:]]+([a-zA-Z0-9_\.\*]+).*/\2/')
        if [ -n "$pattern" ] && [ "$pattern" != "class" ]; then
            echo "$pattern"
        fi
    done | sort -u
}

is_class_kept() {
    local class_name="$1"
    local patterns="$2"
    
    for pattern in $patterns; do
        local regex
        regex=$(echo "$pattern" | sed 's/\./\\./g' | sed 's/\*\*/.*/g' | sed 's/\*/[^.]*/g')
        regex="^${regex}$"
        if [[ "$class_name" =~ $regex ]]; then
            return 0
        fi
    done
    return 1
}

get_mapping_status() {
    local class_name="$1"
    local mapping_file="$2"
    if [ -z "$mapping_file" ] || [ ! -f "$mapping_file" ]; then
        echo "not-minified"
        return
    fi
    
    local escaped_class
    escaped_class=$(echo "$class_name" | sed 's/\./\\./g')
    if grep -q "^${escaped_class} ->" "$mapping_file"; then
        local line
        line=$(grep "^${escaped_class} ->" "$mapping_file" | head -n 1)
        local original mapped
        original=$(echo "$line" | sed -E 's/ ->.*//')
        mapped=$(echo "$line" | sed -E 's/.* -> (.*):/\1/')
        if [ "$original" = "$mapped" ]; then
            echo "kept-original"
        else
            echo "obfuscated-to-$mapped"
        fi
    else
        echo "No"
    fi
}

log_info "Analyzing project structures..."
source_classes=$(find_source_classes)
manifest_classes=$(get_manifest_classes "$APK_PATH" "$AAPT2")
dex_classes=$( "$DEXDUMP" "$APK_PATH" 2>/dev/null | grep "Class descriptor" | sed -E "s/.*: 'L(.*);'/\1/" | tr '/' '.' | grep "^com\.inscopelabs\.abxmcp" | sort -u || true )
proguard_patterns=$(get_proguard_patterns)

# Master set of all project classes
all_classes=$(echo -e "${source_classes}\n${manifest_classes}\n${dex_classes}" | grep -v "^$" | sort -u)

log_info "Verifying keep rule coverage table..."
echo "----------------------------------------------------------------------------------------------------------------------------------------"
printf "%-55s | %-25s | %-8s | %-20s | %-15s\n" "Class Name" "Expected Keep Source" "In DEX" "In mapping.txt" "AAPT Rule Status"
echo "----------------------------------------------------------------------------------------------------------------------------------------"

json_items=""
v7_signature_failures=0

for cls in $all_classes; do
    # 1. Expected Keep Source
    in_manifest=false
    if echo "$manifest_classes" | grep -q "^${cls}$"; then
        in_manifest=true
    fi
    
    is_explicit=false
    if is_class_kept "$cls" "$proguard_patterns"; then
        is_explicit=true
    fi
    
    keep_source="none"
    if [ "$in_manifest" = true ] && [ "$is_explicit" = true ]; then
        keep_source="explicit + manifest"
    elif [ "$in_manifest" = true ]; then
        keep_source="manifest-derived"
    elif [ "$is_explicit" = true ]; then
        keep_source="explicit rule"
    fi
    
    # 2. In DEX
    in_dex="No"
    if echo "$dex_classes" | grep -q "^${cls}$"; then
        in_dex="Yes"
    fi
    
    # 3. In mapping.txt
    mapping_status=$(get_mapping_status "$cls" "$MAPPING_FILE")
    
    # 4. AAPT Rule Status
    aapt_status="N/A"
    if [ "$in_manifest" = true ]; then
        if [ -n "$AAPT_RULES" ] && [ -f "$AAPT_RULES" ]; then
            if grep -q "$cls" "$AAPT_RULES"; then
                aapt_status="OK"
            else
                aapt_status="MISSING"
                v7_signature_failures=$((v7_signature_failures + 1))
            fi
        else
            aapt_status="No rules file"
        fi
    fi
    
    # Print row
    # Highlight failures/missing elements with coloring
    print_in_dex="$in_dex"
    print_aapt_status="$aapt_status"
    if [ "$in_dex" = "No" ] && [ "$keep_source" != "none" ]; then
        print_in_dex="\033[1;31mNo\033[0m"
    fi
    if [ "$aapt_status" = "MISSING" ]; then
        print_aapt_status="\033[1;31mMISSING\033[0m"
    fi
    
    printf "%-55s | %-25s | %-8b | %-20s | %-15b\n" "$cls" "$keep_source" "$print_in_dex" "$mapping_status" "$print_aapt_status"
    
    # Construct JSON item
    item_json=$(cat <<EOF
    {
      "class_name": "$cls",
      "expected_keep_source": "$keep_source",
      "in_dex": $(if [ "$in_dex" = "Yes" ]; then echo "true"; else echo "false"; fi),
      "mapping_status": "$mapping_status",
      "aapt_rule_status": "$aapt_status"
    }
EOF
)
    if [ -z "$json_items" ]; then
        json_items="$item_json"
    else
        json_items="$json_items,$item_json"
    fi
done
echo "----------------------------------------------------------------------------------------------------------------------------------------"

# Write out separate JSON file if requested
if [ -n "$OUTPUT_JSON" ]; then
    cat <<EOF > "$OUTPUT_JSON"
[
$json_items
]
EOF
fi

if [ "$v7_signature_failures" -gt 0 ]; then
    log_fail "Detected $v7_signature_failures class(es) with the v7 manifest-mismatch signature!"
    exit 1
else
    log_pass "Keep-rule coverage check completed successfully with zero v7 signature mismatches."
    exit 0
fi
