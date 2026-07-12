import sys
import json

def validate(scorecard_path, schema_path):
    try:
        with open(scorecard_path, 'r') as f:
            scorecard = json.load(f)
        with open(schema_path, 'r') as f:
            schema = json.load(f)
    except Exception as e:
        print(f"Error loading files: {e}")
        return False
        
    # Manual schema validation of metadata
    meta = scorecard.get("metadata")
    if not isinstance(meta, dict):
        print("Error: metadata must be a dictionary")
        return False
        
    required_meta = ["versionCode", "versionName", "commit_sha", "workflow_run_id", "timestamp", "aab_size_bytes", "overall_status"]
    for field in required_meta:
        if field not in meta:
            print(f"Error: metadata missing required field: {field}")
            return False
            
    if not isinstance(meta["versionCode"], int):
        print("Error: versionCode must be an integer")
        return False
    if not isinstance(meta["versionName"], str):
        print("Error: versionName must be a string")
        return False
    if not isinstance(meta["commit_sha"], str):
        print("Error: commit_sha must be a string")
        return False
    if not isinstance(meta["workflow_run_id"], str):
        print("Error: workflow_run_id must be a string")
        return False
    if not isinstance(meta["timestamp"], str):
        print("Error: timestamp must be a string")
        return False
    if not isinstance(meta["aab_size_bytes"], int):
        print("Error: aab_size_bytes must be an integer")
        return False
    if meta["overall_status"] not in ["PASS", "FAIL"]:
        print("Error: overall_status must be either PASS or FAIL")
        return False
        
    # Validate results
    results = scorecard.get("results")
    if not isinstance(results, list):
        print("Error: results must be a list")
        return False
        
    required_result_fields = ["id", "name", "check_type", "severity", "status", "evidence"]
    for i, res in enumerate(results):
        if not isinstance(res, dict):
            print(f"Error: result item at index {i} must be a dictionary")
            return False
        for field in required_result_fields:
            if field not in res:
                print(f"Error: result item at index {i} missing required field: {field}")
                return False
        if res["check_type"] not in ["automatable", "manual-review"]:
            print(f"Error: invalid check_type in result item {i}: {res['check_type']}")
            return False
        if res["severity"] not in ["blocking", "warning"]:
            print(f"Error: invalid severity in result item {i}: {res['severity']}")
            return False
        if res["status"] not in ["pass", "fail", "not-applicable", "manual-review"]:
            print(f"Error: invalid status in result item {i}: {res['status']}")
            return False
        if not isinstance(res["evidence"], str):
            print(f"Error: evidence in result item {i} must be a string")
            return False
            
    print("Schema validation PASSED successfully.")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 validate-scorecard.py <scorecard_json> <schema_json>")
        sys.exit(1)
    if validate(sys.argv[1], sys.argv[2]):
        sys.exit(0)
    else:
        sys.exit(1)
