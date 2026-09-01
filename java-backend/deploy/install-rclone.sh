#!/usr/bin/env bash
set -euo pipefail

version=1.75.0
package="rclone-v${version}-linux-amd64.deb"
url="https://downloads.rclone.org/v${version}/${package}"
expected=266598b5c66a42b821571332013cc85a88ffcff8939cf0643861c8d033dd3a4a
temporary="/tmp/${package}"

curl --fail --location --show-error --silent \
  --connect-timeout 15 --max-time 300 \
  --output "$temporary" "$url"
printf '%s  %s\n' "$expected" "$temporary" | sha256sum --check --strict
dpkg --install "$temporary"

install -d -o lfp -g lfp -m 0700 \
  /home/lfp/.config/rclone \
  /home/lfp/pdfbrowser/google-drive \
  /home/lfp/pdfbrowser/rclone-cache

rclone version
