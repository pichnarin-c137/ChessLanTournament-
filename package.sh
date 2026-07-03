#!/usr/bin/env bash
# Builds the fat jar and packages it as a .deb with jpackage.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=1.0.0
JAR="chess-referee-${VERSION}-all.jar"

mvn -q clean package

rm -rf target/dist
mkdir -p target/dist
cp "target/${JAR}" target/dist/

jpackage \
  --type deb \
  --name chess-referee \
  --app-version "${VERSION}" \
  --description "LAN chess referee: two players join by QR code from their browsers" \
  --input target/dist \
  --main-jar "${JAR}" \
  --main-class chess.Main \
  --linux-menu-group Games \
  --linux-shortcut \
  --dest target

echo "Built: $(ls target/*.deb)"
