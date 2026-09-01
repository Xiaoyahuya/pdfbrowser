#!/usr/bin/env bash
set -euo pipefail

work_dir="$(mktemp -d /tmp/pdfbrowser-runtime.XXXXXX)"
test_root="$work_dir/files"
test_port=18084
jar_path="${PDFBROWSER_JAR:-/home/lfp/pdfbrowser/java-backend/deploy/pdfbrowser-server.jar}"
test_pid=

cleanup() {
  if [[ -n "$test_pid" ]] && kill -0 "$test_pid" 2>/dev/null; then
    kill "$test_pid"
    wait "$test_pid" 2>/dev/null || true
  fi
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

mkdir -p "$test_root"
cp /home/lfp/pdfbrowser/README.md "$test_root/sample.md"
curl --fail --silent --show-error \
  --proxy http://127.0.0.1:7897 \
  --max-time 60 \
  --output "$test_root/sample.pdf" \
  https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf

if ss -ltnH "sport = :$test_port" | grep -q .; then
  echo "TEST_PORT_ALREADY_IN_USE=$test_port"
  exit 1
fi

PDFBROWSER_ROOT="$test_root" \
SERVER_ADDRESS=127.0.0.1 \
PORT="$test_port" \
java -Xms64m -Xmx256m -jar \
  "$jar_path" \
  >"$work_dir/app.log" 2>&1 &
test_pid=$!

for _ in $(seq 1 30); do
  if curl --fail --silent --max-time 2 \
    "http://127.0.0.1:$test_port/" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$test_pid" 2>/dev/null; then
    echo "TEST_INSTANCE_EXITED=1"
    tail -n 20 "$work_dir/app.log"
    exit 1
  fi
  sleep 1
done

curl --fail --silent --show-error --max-time 2 \
  "http://127.0.0.1:$test_port/" >/dev/null

base="http://127.0.0.1:$test_port/api/files"
echo "TEST_ROOT_ENTRIES=$(curl --fail --silent --show-error "$base" | jq '.entries | length')"

md_status="$(curl --silent --show-error --get \
  --data-urlencode 'path=sample.md' \
  --dump-header "$work_dir/md.headers" \
  --output "$work_dir/md.body" \
  --write-out '%{http_code}' \
  "$base/markdown")"
echo "MARKDOWN_STATUS=$md_status"
echo "MARKDOWN_CONTENT_TYPE=$(grep -ci '^content-type: text/markdown' "$work_dir/md.headers")"
echo "MARKDOWN_BYTES=$(wc -c <"$work_dir/md.body")"

pdf_status="$(curl --silent --show-error --get \
  --data-urlencode 'path=sample.pdf' \
  --header 'Range: bytes=0-99' \
  --dump-header "$work_dir/pdf.headers" \
  --output "$work_dir/pdf.body" \
  --write-out '%{http_code}' \
  "$base/raw")"
echo "PDF_RANGE_STATUS=$pdf_status"
echo "PDF_RANGE_BYTES=$(wc -c <"$work_dir/pdf.body")"
echo "PDF_CONTENT_RANGE=$(grep -ci '^content-range: bytes 0-99/' "$work_dir/pdf.headers")"
echo "PDF_SIGNATURE=$(head -c 5 "$work_dir/pdf.body")"
if [[ "$pdf_status" != 206 ]]; then
  echo PDF_ERROR_LOG
  grep -A 60 'Unexpected error while handling' "$work_dir/app.log" | tail -n 80 || true
fi

echo "STORAGE_WRITABLE=$(curl --fail --silent --show-error "$base/capabilities" | jq -r '.writable')"
upload_status="$(curl --silent --show-error \
  --request POST \
  --form "file=@$test_root/sample.md" \
  --output "$work_dir/upload.body" \
  --write-out '%{http_code}' \
  "$base/upload?path=")"
echo "READ_ONLY_UPLOAD_STATUS=$upload_status"
