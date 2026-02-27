#!/usr/bin/env bash
# =============================================================================
# analyze_kb.sh
# Iterates over every file inside kb/<subfolder>/, sends each file to the
# analysis API, and writes per-subfolder logs.txt and usage.txt files.
#
# Response headers X-OpenAI-Tokens and X-Anthropic-Tokens are set by the
# Spring backend and contain the cumulative token usage for that request.
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KB_DIR="$SCRIPT_DIR/kb"
API_URL="http://localhost:8085/api/analyze"

# ── Pre-flight checks ─────────────────────────────────────────────────────────
if [[ ! -d "$KB_DIR" ]]; then
  echo "ERROR: directory not found: $KB_DIR" >&2
  exit 1
fi

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl not found" >&2; exit 1; }

echo "======================================================"
echo "  SWE Audit — batch analysis"
echo "  kb dir : $KB_DIR"
echo "  API    : $API_URL"
echo "  Date   : $(date)"
echo "======================================================"
echo ""

# ── Process each subfolder ────────────────────────────────────────────────────
for subdir in "$KB_DIR"/*/; do
  [[ -d "$subdir" ]] || continue
  subdir_name="$(basename "$subdir")"

  # Collect files at depth 1, excluding our own output files
  files=()
  while IFS= read -r -d '' f; do
    files+=("$f")
  done < <(
    find "$subdir" -maxdepth 1 -type f \
      ! -name "logs.txt"   \
      ! -name "usage.txt"  \
      ! -name "response_*" \
      ! -name "fuzzy_discarded_*.json" \
      -print0 | sort -z
  )

  if [[ ${#files[@]} -eq 0 ]]; then
    echo "[$subdir_name] No files found — skipping."
    echo ""
    continue
  fi

  LOGS_FILE="$subdir/logs.txt"
  USAGE_FILE="$subdir/usage.txt"

  # (Re)initialise log files for this run
  {
    echo "======================================================"
    echo "  Analysis log — $subdir_name"
    echo "  Run date : $(date)"
    echo "======================================================"
    echo ""
  } > "$LOGS_FILE"

  {
    echo "======================================================"
    echo "  Token & timing usage — $subdir_name"
    echo "  Run date : $(date)"
    echo "======================================================"
    echo ""
  } > "$USAGE_FILE"

  echo "[$subdir_name] ${#files[@]} file(s) to process"

  for file in "${files[@]}"; do
    filename="$(basename "$file")"
    output_file="$subdir/response_${filename%.*}.pdf"
    headers_tmp="$(mktemp)"

    echo "  -> $filename"

    # ── Call the API ─────────────────────────────────────────────────────────
    # -s        : silent (no progress bar)
    # -o        : save response body to file
    # -D        : dump response headers to temp file
    # -w        : write http_code and time_total after body
    # The two values come after the body and are separated by ","
    curl_write_out=""
    curl_write_out=$(
      curl -s \
        -o "$output_file" \
        -D "$headers_tmp" \
        -w "%{http_code},%{time_total}" \
        -X POST "$API_URL" \
        -F "file=@${file}" \
        2>&1
    ) || true   # never exit on curl failure

    HTTP_CODE="$(echo "$curl_write_out" | cut -d',' -f1)"
    EXEC_TIME="$(echo "$curl_write_out" | cut -d',' -f2)"

    # Sanitise values against empty responses (e.g. connection refused)
    [[ -z "$HTTP_CODE" ]] && HTTP_CODE="000"
    [[ -z "$EXEC_TIME" ]] && EXEC_TIME="0"

    # ── Extract token headers ─────────────────────────────────────────────────
    extract_header() { grep -i "^$1:" "$headers_tmp" 2>/dev/null | head -1 | awk '{print $2}' | tr -d '\r\n'; }

    OAI_IN=$(extract_header  "x-openai-input-tokens");     [[ -z "$OAI_IN"  ]] && OAI_IN="N/A"
    OAI_OUT=$(extract_header "x-openai-output-tokens");    [[ -z "$OAI_OUT" ]] && OAI_OUT="N/A"
    OAI_TOT=$(extract_header "x-openai-total-tokens");     [[ -z "$OAI_TOT" ]] && OAI_TOT="N/A"
    ANT_IN=$(extract_header  "x-anthropic-input-tokens");  [[ -z "$ANT_IN"  ]] && ANT_IN="N/A"
    ANT_OUT=$(extract_header "x-anthropic-output-tokens"); [[ -z "$ANT_OUT" ]] && ANT_OUT="N/A"
    ANT_TOT=$(extract_header "x-anthropic-total-tokens");  [[ -z "$ANT_TOT" ]] && ANT_TOT="N/A"
    FUZZY_DISCARDED_COUNT=$(extract_header "x-fuzzy-discarded-count"); [[ -z "$FUZZY_DISCARDED_COUNT" ]] && FUZZY_DISCARDED_COUNT="0"
    FUZZY_DISCARDED_ISSUES=$(extract_header "x-fuzzy-discarded-issues"); [[ -z "$FUZZY_DISCARDED_ISSUES" ]] && FUZZY_DISCARDED_ISSUES="none"
    FUZZY_DISCARDED_JSON_B64=$(extract_header "x-fuzzy-discarded-json-b64")

    # Pipeline stage timings (for paper / evaluation)
    STAGE_EXTRACTION=$(extract_header "x-pipeline-stage-extraction-seconds");   [[ -z "$STAGE_EXTRACTION" ]] && STAGE_EXTRACTION="N/A"
    STAGE_PARALLEL=$(extract_header "x-pipeline-stage-parallelagents-seconds");  [[ -z "$STAGE_PARALLEL" ]] && STAGE_PARALLEL="N/A"
    STAGE_EVIDENCE=$(extract_header "x-pipeline-stage-evidencededup-seconds");   [[ -z "$STAGE_EVIDENCE" ]] && STAGE_EVIDENCE="N/A"
    STAGE_REPORT=$(extract_header "x-pipeline-stage-reportgen-seconds");       [[ -z "$STAGE_REPORT" ]] && STAGE_REPORT="N/A"
    PIPELINE_TOTAL=$(extract_header "x-pipeline-total-seconds");                [[ -z "$PIPELINE_TOTAL" ]] && PIPELINE_TOTAL="N/A"

    # ── Detect content type (for log readability) ─────────────────────────────
    CONTENT_TYPE="$(grep -i '^content-type:' "$headers_tmp" 2>/dev/null \
                     | head -1 | awk '{print $2}' | tr -d '\r\n')"

    # If the server returned an error (JSON body), capture it too
    error_body=""
    if [[ "$CONTENT_TYPE" == *"application/json"* ]] && [[ -f "$output_file" ]]; then
      error_body="$(cat "$output_file" 2>/dev/null)"
    fi

    fuzzy_sidecar=""
    if [[ -n "$FUZZY_DISCARDED_JSON_B64" ]]; then
      fuzzy_sidecar="$subdir/fuzzy_discarded_${filename%.*}.json"
      python3 - "$FUZZY_DISCARDED_JSON_B64" "$fuzzy_sidecar" <<'PY'
import base64
import json
import pathlib
import sys

encoded = sys.argv[1].strip()
target = pathlib.Path(sys.argv[2])

if not encoded:
    sys.exit(0)

padding = '=' * ((4 - len(encoded) % 4) % 4)
raw = base64.urlsafe_b64decode(encoded + padding)
obj = json.loads(raw.decode("utf-8"))

target.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
PY
    fi

    # ── Append to logs.txt ────────────────────────────────────────────────────
    {
      echo "------------------------------------------------------"
      echo "File             : $filename"
      echo "Timestamp        : $(date)"
      echo "HTTP status      : $HTTP_CODE"
      echo "Content-Type     : ${CONTENT_TYPE:-unknown}"
      echo "Execution time   : ${EXEC_TIME}s"
      echo "OpenAI tokens    : in=$OAI_IN  out=$OAI_OUT  tot=$OAI_TOT"
      echo "Anthropic tokens : in=$ANT_IN  out=$ANT_OUT  tot=$ANT_TOT"
      echo "Fuzzy discarded  : count=$FUZZY_DISCARDED_COUNT"
      if [[ "$FUZZY_DISCARDED_ISSUES" != "none" ]]; then
        echo "Fuzzy discarded issues:"
        echo "$FUZZY_DISCARDED_ISSUES"
      fi
      if [[ -n "$fuzzy_sidecar" ]]; then
        echo "Fuzzy sidecar    : $(basename "$fuzzy_sidecar")"
      fi
      echo ""
      echo "Response headers:"
      cat "$headers_tmp"
      if [[ -n "$error_body" ]]; then
        echo ""
        echo "Error body:"
        echo "$error_body"
      fi
      echo ""
    } >> "$LOGS_FILE"

    # ── Append to usage.txt ───────────────────────────────────────────────────
    {
      echo "--- $filename ---"
      echo "openai_input_tokens: $OAI_IN"
      echo "openai_output_tokens: $OAI_OUT"
      echo "openai_total_tokens: $OAI_TOT"
      echo "anthropic_input_tokens: $ANT_IN"
      echo "anthropic_output_tokens: $ANT_OUT"
      echo "anthropic_total_tokens: $ANT_TOT"
      echo "fuzzy_discarded_count: $FUZZY_DISCARDED_COUNT"
      echo "fuzzy_discarded_sidecar: ${fuzzy_sidecar:+$(basename "$fuzzy_sidecar")}"
      echo "execution_time_seconds: $EXEC_TIME"
      echo "pipeline_stage_extraction_seconds: $STAGE_EXTRACTION"
      echo "pipeline_stage_parallel_agents_seconds: $STAGE_PARALLEL"
      echo "pipeline_stage_evidence_dedup_seconds: $STAGE_EVIDENCE"
      echo "pipeline_stage_report_gen_seconds: $STAGE_REPORT"
      echo "pipeline_total_seconds: $PIPELINE_TOTAL"
      echo ""
    } >> "$USAGE_FILE"

    rm -f "$headers_tmp"

    # ── Console summary ───────────────────────────────────────────────────────
    if [[ "$HTTP_CODE" == "200" ]]; then
      echo "     OK  | OAI: ${OAI_IN}in/${OAI_OUT}out | ANT: ${ANT_IN}in/${ANT_OUT}out | fuzzy_discarded=${FUZZY_DISCARDED_COUNT} | Time: ${EXEC_TIME}s"
    else
      echo "     FAIL (HTTP $HTTP_CODE) | Time: ${EXEC_TIME}s"
      if [[ -n "$error_body" ]]; then
        echo "     Error: $error_body"
      fi
    fi
  done

  echo "  [$subdir_name] Done — logs: logs.txt | usage: usage.txt"
  echo ""
done

echo "======================================================"
echo "  All subfolders processed."
echo "======================================================"
