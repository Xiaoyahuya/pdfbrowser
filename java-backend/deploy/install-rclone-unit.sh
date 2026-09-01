#!/usr/bin/env bash
set -euo pipefail

deploy_dir=/home/lfp/pdfbrowser/java-backend/deploy
unit_name=rclone-google-drive.service

install -d -o lfp -g lfp -m 0700 \
  /home/lfp/.config/rclone \
  /home/lfp/pdfbrowser/google-drive \
  /home/lfp/pdfbrowser/rclone-cache
install -o root -g root -m 0644 \
  "$deploy_dir/$unit_name" \
  "/etc/systemd/system/$unit_name"
systemctl daemon-reload

echo "installed-disabled"
systemctl is-enabled "$unit_name" 2>/dev/null || true
