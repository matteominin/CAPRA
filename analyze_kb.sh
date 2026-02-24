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

    # ── Detect content type (for log readability) ─────────────────────────────
    CONTENT_TYPE="$(grep -i '^content-type:' "$headers_tmp" 2>/dev/null \
                     | head -1 | awk '{print $2}' | tr -d '\r\n')"

    # If the server returned an error (JSON body), capture it too
    error_body=""
    if [[ "$CONTENT_TYPE" == *"application/json"* ]] && [[ -f "$output_file" ]]; then
      error_body="$(cat "$output_file" 2>/dev/null)"
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
      echo "execution_time_seconds: $EXEC_TIME"
      echo ""
    } >> "$USAGE_FILE"

    rm -f "$headers_tmp"

    # ── Console summary ───────────────────────────────────────────────────────
    if [[ "$HTTP_CODE" == "200" ]]; then
      echo "     OK  | OAI: ${OAI_IN}in/${OAI_OUT}out | ANT: ${ANT_IN}in/${ANT_OUT}out | Time: ${EXEC_TIME}s"
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
