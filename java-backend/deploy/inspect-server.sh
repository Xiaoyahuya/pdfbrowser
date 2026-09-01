#!/usr/bin/env bash
set -u

echo SUDO_OK
java -version 2>&1
echo RESOURCES
free -h
echo JAR
sha256sum \
  /home/lfp/pdfbrowser/java-backend/deploy/pdfbrowser-server.jar \
  /home/lfp/pdfbrowser/java-backend/deploy/pdfbrowser-server.jar.corrupt-20260809 2>/dev/null || true
ls -l /home/lfp/pdfbrowser/java-backend/deploy
echo SERVICES
systemctl list-unit-files --type=service | grep -Ei 'pdf|browser|rclone' || true
systemctl cat filebrowser.service 2>/dev/null || true
systemctl status filebrowser.service --no-pager -l 2>/dev/null | head -n 20 || true
echo PROCESSES
ps -eo pid,user,cmd | grep -Ei 'pdfbrowser|java.*jar' | grep -v grep || true
echo DOCKER
docker ps --format '{{.Names}}|{{.Ports}}|{{.Image}}' 2>/dev/null || true
echo NGINX
grep -RHE '^[[:space:]]*(listen|server_name|location|proxy_pass|root)[[:space:]]' \
  /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null || true
echo FIREWALL
ufw status 2>/dev/null || true
echo PROXY
systemctl list-units --type=service --state=running --no-legend | grep -Ei 'clash|proxy|sing|v2ray|xray' || true
ss -ltn 2>/dev/null | grep -E ':(7890|7891|1080|10808|10809)[[:space:]]' || true
