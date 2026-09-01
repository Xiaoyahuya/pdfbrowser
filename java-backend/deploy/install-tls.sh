#!/usr/bin/env bash
set -euo pipefail

tls_dir=/etc/nginx/pdfbrowser-tls
certificate="$tls_dir/server.crt"
private_key="$tls_dir/server.key"

install -d -o root -g root -m 0700 "$tls_dir"
if [[ ! -s "$certificate" || ! -s "$private_key" ]]; then
  temporary_dir="$(mktemp -d)"
  cleanup() {
    rm -f -- "$temporary_dir/server.key" "$temporary_dir/server.crt"
    rmdir -- "$temporary_dir" 2>/dev/null || true
  }
  trap cleanup EXIT
  openssl req -x509 -nodes -newkey rsa:3072 -sha256 -days 825 \
    -subj "/CN=110.42.101.86" \
    -addext "subjectAltName=IP:110.42.101.86" \
    -addext "keyUsage=digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth" \
    -keyout "$temporary_dir/server.key" \
    -out "$temporary_dir/server.crt"
  install -o root -g root -m 0600 "$temporary_dir/server.key" "$private_key"
  install -o root -g root -m 0644 "$temporary_dir/server.crt" "$certificate"
fi

openssl x509 -in "$certificate" -noout -fingerprint -sha256
