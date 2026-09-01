#!/usr/bin/env bash
set -euo pipefail

runtime_dir=/home/lfp/pdfbrowser/nas-runtime
environment_file=/home/lfp/pdfbrowser/java-backend/deploy/pdfbrowser.env

install -d -o lfp -g lfp -m 0700 \
  "$runtime_dir" \
  "$runtime_dir/mounts" \
  "$runtime_dir/configs" \
  "$runtime_dir/logs"

if ! grep -q '^PDFBROWSER_NAS_ENABLED=' "$environment_file"; then
  printf '%s\n' 'PDFBROWSER_NAS_ENABLED=true' >> "$environment_file"
fi
if ! grep -q '^PDFBROWSER_NAS_RUNTIME=' "$environment_file"; then
  printf '%s\n' 'PDFBROWSER_NAS_RUNTIME=/home/lfp/pdfbrowser/nas-runtime' >> "$environment_file"
fi
if ! grep -q '^PDFBROWSER_NAS_CREDENTIAL_KEY=' "$environment_file"; then
  credential_key="$(openssl rand -base64 32 | tr -d '\n')"
  printf 'PDFBROWSER_NAS_CREDENTIAL_KEY=%s\n' "$credential_key" >> "$environment_file"
  unset credential_key
fi
if ! grep -q '^PDFBROWSER_NAS_ALLOW_PUBLIC_HOSTS=' "$environment_file"; then
  printf '%s\n' 'PDFBROWSER_NAS_ALLOW_PUBLIC_HOSTS=false' >> "$environment_file"
fi
if ! grep -q '^PDFBROWSER_NAS_MOUNT_TIMEOUT_SECONDS=' "$environment_file"; then
  printf '%s\n' 'PDFBROWSER_NAS_MOUNT_TIMEOUT_SECONDS=20' >> "$environment_file"
fi
if ! grep -q '^PDFBROWSER_NAS_SOCKS_HOST=' "$environment_file"; then
  printf '%s\n' 'PDFBROWSER_NAS_SOCKS_HOST=127.0.0.1' >> "$environment_file"
fi
if ! grep -q '^PDFBROWSER_NAS_SOCKS_PORT=' "$environment_file"; then
  printf '%s\n' 'PDFBROWSER_NAS_SOCKS_PORT=7897' >> "$environment_file"
fi

chown lfp:lfp "$environment_file"
chmod 0600 "$environment_file"
