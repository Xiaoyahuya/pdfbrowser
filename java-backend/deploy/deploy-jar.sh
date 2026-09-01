#!/usr/bin/env bash
set -euo pipefail

source_jar=/home/lfp/pdfbrowser/java-backend/target/pdfbrowser-server-1.0.0-SNAPSHOT.jar
deployed_jar=/home/lfp/pdfbrowser/java-backend/deploy/pdfbrowser-server.jar
new_jar="${deployed_jar}.new"
previous_jar="${deployed_jar}.previous"

cp "$source_jar" "$new_jar"
chown lfp:lfp "$new_jar"
chmod 0640 "$new_jar"
/usr/bin/jar tf "$new_jar" >/dev/null

systemctl stop pdfbrowser-vuefinder.service
cp -p "$deployed_jar" "$previous_jar"
mv "$new_jar" "$deployed_jar"

if ! systemctl start pdfbrowser-vuefinder.service; then
  cp -p "$previous_jar" "$deployed_jar"
  systemctl start pdfbrowser-vuefinder.service
  exit 1
fi

sha256sum "$deployed_jar"
stat -c 'size=%s' "$deployed_jar"
