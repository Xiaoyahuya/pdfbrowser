#!/usr/bin/env bash
set -euo pipefail

source_config=/home/lfp/pdfbrowser/java-backend/deploy/secure/mihomo-tank.yaml
live_config=/home/lfp/.config/mihomo/config.yaml
backup_config=/home/lfp/.config/mihomo/config.yaml.before-tank

if [[ ! -s "$source_config" ]]; then
  echo "未找到 tank 配置文件" >&2
  exit 1
fi
if ! grep -qF "Tank OpenVPN" "$source_config"; then
  echo "配置中没有找到 Tank OpenVPN，拒绝覆盖" >&2
  exit 1
fi

install -o lfp -g lfp -m 0600 "$source_config" /home/lfp/.config/mihomo/config.yaml.candidate
sudo -u lfp /usr/local/bin/mihomo -t -d /home/lfp/.config/mihomo \
  -f /home/lfp/.config/mihomo/config.yaml.candidate
if [[ ! -f "$backup_config" ]]; then
  cp -p "$live_config" "$backup_config"
fi
mv /home/lfp/.config/mihomo/config.yaml.candidate "$live_config"
chown lfp:lfp "$live_config"
chmod 0600 "$live_config"

if systemctl restart mihomo-rclone.service \
    && sleep 2 \
    && systemctl is-active --quiet mihomo-rclone.service \
    && ss -ltn | grep -q "127.0.0.1:7897" \
    && curl --silent --show-error --max-time 15 \
      --proxy http://127.0.0.1:7897 \
      --output /dev/null https://www.googleapis.com/; then
  echo "Tank 配置已启用，代理仅监听 127.0.0.1:7897"
else
  cp -p "$backup_config" "$live_config"
  chown lfp:lfp "$live_config"
  systemctl restart mihomo-rclone.service
  echo "Tank 配置启动失败，已经恢复旧配置" >&2
  exit 1
fi
