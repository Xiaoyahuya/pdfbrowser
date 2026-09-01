#!/usr/bin/env bash
set -euo pipefail

unit_name=pdfbrowser-vuefinder.service

systemctl restart mihomo-rclone.service
systemctl restart rclone-google-drive.service
systemctl restart "$unit_name"
for _ in $(seq 1 30); do
  if curl --fail --silent --show-error --max-time 2 \
    http://127.0.0.1:18082/ >/tmp/pdfbrowser-index.html; then
    break
  fi
  sleep 1
done

echo "ACTIVE=$(systemctl is-active "$unit_name")"
echo "ENABLED=$(systemctl is-enabled "$unit_name")"
echo "LISTENER=$(ss -ltnH 'sport = :18082')"
echo "INDEX_APP_COUNT=$(grep -c 'id="app"' /tmp/pdfbrowser-index.html)"
echo "PROXY_ACTIVE=$(systemctl is-active mihomo-rclone.service)"
echo "RCLONE_ACTIVE=$(systemctl is-active rclone-google-drive.service)"
echo "RCLONE_MOUNT=$(mountpoint -q /home/lfp/pdfbrowser/google-drive && echo yes || echo no)"
echo "ROOT_ENTRIES=$(curl --fail --silent --show-error --max-time 30 'http://127.0.0.1:18082/api/files?path=' | jq '.entries | length')"
