#!/usr/bin/env bash
set -euo pipefail

root=/home/lfp/pdfbrowser/google-drive
api=http://127.0.0.1:18082/api/files
work_dir="$(mktemp -d /tmp/pdfbrowser-verify.XXXXXX)"
trap 'rm -rf -- "$work_dir"' EXIT

echo "PROXY_ACTIVE=$(systemctl is-active mihomo-rclone.service)"
echo "RCLONE_ACTIVE=$(systemctl is-active rclone-google-drive.service)"
echo "APP_ACTIVE=$(systemctl is-active pdfbrowser-vuefinder.service)"
echo "RCLONE_MOUNT=$(mountpoint -q "$root" && echo yes || echo no)"
echo "ROOT_ENTRIES=$(curl --fail --silent --show-error "$api" | jq '.entries | length')"

pdf_path="$(timeout 90 find "$root" -maxdepth 6 -type f -iname '*.pdf' -print -quit || true)"
md_path="$(timeout 90 find "$root" -maxdepth 6 -type f \( -iname '*.md' -o -iname '*.markdown' \) -print -quit || true)"

if [[ -n "$pdf_path" ]]; then
  echo "PDF_STAT_SIZE=$(stat -c '%s' "$pdf_path")"
  if [[ "$(stat -c '%s' "$pdf_path")" == 0 ]]; then
    echo "PDF_FUSE_READ_BYTES=$(timeout 60 head -c 100 "$pdf_path" | wc -c)"
    echo "PDF_STAT_SIZE_AFTER_READ=$(stat -c '%s' "$pdf_path")"
  fi
  pdf_status="$(curl --silent --show-error --get \
    --data-urlencode "path=${pdf_path#"$root"/}" \
    --header 'Range: bytes=0-99' \
    --dump-header "$work_dir/pdf.headers" \
    --output "$work_dir/pdf.body" \
    --write-out '%{http_code}' \
    "$api/raw")"
  echo "PDF_RANGE_STATUS=$pdf_status"
  echo "PDF_RANGE_BYTES=$(wc -c <"$work_dir/pdf.body")"
  echo "PDF_CONTENT_RANGE=$(grep -ci '^content-range: bytes 0-99/' "$work_dir/pdf.headers")"
  if [[ "$pdf_status" != 206 ]]; then
    echo "PDF_ERROR_CODE=$(jq -r '.code // "UNKNOWN"' "$work_dir/pdf.body")"
    echo "PDF_ERROR_MESSAGE=$(jq -r '.message // "UNKNOWN"' "$work_dir/pdf.body")"
  fi
else
  echo "PDF_RANGE_STATUS=SKIPPED_NO_PDF_WITHIN_DEPTH_6"
fi

if [[ -n "$md_path" ]]; then
  md_status="$(curl --silent --show-error --get \
    --data-urlencode "path=${md_path#"$root"/}" \
    --dump-header "$work_dir/md.headers" \
    --output "$work_dir/md.body" \
    --write-out '%{http_code}' \
    "$api/markdown")"
  echo "MARKDOWN_STATUS=$md_status"
  echo "MARKDOWN_CONTENT_TYPE=$(grep -ci '^content-type: text/markdown' "$work_dir/md.headers")"
else
  echo "MARKDOWN_STATUS=SKIPPED_NO_MARKDOWN_WITHIN_DEPTH_6"
fi

traversal_status="$(curl --silent --show-error --get \
  --data-urlencode 'path=../' \
  --output /dev/null \
  --write-out '%{http_code}' \
  "$api")"
echo "PATH_TRAVERSAL_STATUS=$traversal_status"
