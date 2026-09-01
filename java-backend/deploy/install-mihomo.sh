#!/usr/bin/env bash
set -euo pipefail

version=1.19.29
archive="mihomo-linux-amd64-v${version}.gz"
expected=60de76a35a6cbf7b4fa4a20f5c257c24345d1d635ab1aa3877022a1997ef413c
download_url="https://github.com/MetaCubeX/mihomo/releases/download/v${version}/${archive}"
deploy_archive="/home/lfp/pdfbrowser/java-backend/deploy/${archive}"
temporary_dir="$(mktemp -d /tmp/mihomo-install.XXXXXX)"
temporary_archive="$temporary_dir/$archive"
temporary_binary="$temporary_dir/mihomo"
trap 'rm -rf -- "$temporary_dir"' EXIT

if [[ -f "$deploy_archive" ]]; then
  cp "$deploy_archive" "$temporary_archive"
else
  proxy_args=()
  if ss -ltn | grep -q '127.0.0.1:7897'; then
    proxy_args=(--proxy http://127.0.0.1:7897)
  fi
  curl --fail --location --show-error --silent \
    --connect-timeout 15 --max-time 300 \
    "${proxy_args[@]}" \
    --output "$temporary_archive" \
    "$download_url"
fi

printf '%s  %s\n' "$expected" "$temporary_archive" | sha256sum --check --strict
gzip --decompress --stdout "$temporary_archive" >"$temporary_binary"
chmod 0755 "$temporary_binary"
install -o root -g root -m 0755 "$temporary_binary" /usr/local/bin/mihomo
install -d -o lfp -g lfp -m 0700 /home/lfp/.config/mihomo

/usr/local/bin/mihomo -v
