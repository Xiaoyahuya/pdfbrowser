#!/usr/bin/env bash
set -euo pipefail

deploy_dir=/home/lfp/pdfbrowser/java-backend/deploy
unit_name=pdfbrowser-vuefinder.service

/home/lfp/pdfbrowser/java-backend/deploy/install-nas-runtime.sh
chown lfp:lfp \
  "$deploy_dir/pdfbrowser-server.jar" \
  "$deploy_dir/pdfbrowser.env"
chmod 0640 "$deploy_dir/pdfbrowser-server.jar"
chmod 0600 "$deploy_dir/pdfbrowser.env"
install -o root -g root -m 0644 \
  "$deploy_dir/$unit_name" \
  "/etc/systemd/system/$unit_name"

systemctl daemon-reload
systemctl enable --now "$unit_name"

for _ in $(seq 1 30); do
  if curl --fail --silent --show-error --max-time 2 \
    http://127.0.0.1:18082/ >/dev/null; then
    break
  fi
  sleep 1
done

systemctl is-active "$unit_name"
curl --fail --silent --show-error --max-time 5 \
  'http://127.0.0.1:18082/api/files?path='
