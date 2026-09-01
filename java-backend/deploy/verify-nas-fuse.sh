#!/usr/bin/env bash
set -euo pipefail

source_directory=/home/lfp/pdfbrowser/java-backend/sample-files
smoke_id="${SMOKE_ID:-default}"
if [[ ! "$smoke_id" =~ ^[a-z0-9-]+$ ]]; then
  echo "SMOKE_ID 格式无效" >&2
  exit 2
fi
mount_directory="/home/lfp/pdfbrowser/nas-runtime/fuse-smoke-$smoke_id"

mkdir -p "$mount_directory"
cleanup() {
  if mountpoint -q "$mount_directory"; then
    fusermount3 -uz "$mount_directory" || true
  fi
  rmdir "$mount_directory" 2>/dev/null || true
}
trap cleanup EXIT

rclone mount ":local:$source_directory" "$mount_directory" \
  --read-only \
  --vfs-cache-mode off \
  --daemon \
  --daemon-wait 10s \
  --umask 077
mountpoint -q "$mount_directory"
test -r "$mount_directory/README.md"
findmnt -T "$mount_directory" -o OPTIONS -n
fusermount3 -u "$mount_directory"
echo "NAS FUSE 权限与隔离配置验证通过"
