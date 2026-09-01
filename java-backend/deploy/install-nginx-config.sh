#!/usr/bin/env bash
set -euo pipefail

source_config=/home/lfp/pdfbrowser/java-backend/deploy/pdfbrowser-nginx.conf
live_config=/etc/nginx/conf.d/pdfbrowser.conf
backup_config=/etc/nginx/conf.d/pdfbrowser.conf.previous
auth_directory=/etc/nginx/pdfbrowser-auth
auth_file="$auth_directory/htpasswd"
legacy_auth_file=/etc/nginx/.htpasswd-pdfbrowser
source_auth_file=/home/lfp/pdfbrowser/java-backend/deploy/.htpasswd-pdfbrowser

/home/lfp/pdfbrowser/java-backend/deploy/install-tls.sh
install -d -o lfp -g www-data -m 2770 "$auth_directory"
if [[ ! -s "$auth_file" ]]; then
  if [[ -s "$legacy_auth_file" ]]; then
    install -o lfp -g www-data -m 0640 "$legacy_auth_file" "$auth_file"
  else
    install -o lfp -g www-data -m 0640 "$source_auth_file" "$auth_file"
  fi
fi
if [[ -f "$live_config" ]]; then
  cp -p "$live_config" "$backup_config"
fi
install -o root -g root -m 0644 "$source_config" "$live_config"
if nginx -t; then
  systemctl reload nginx
else
  if [[ -f "$backup_config" ]]; then
    cp -p "$backup_config" "$live_config"
  fi
  nginx -t
  exit 1
fi
