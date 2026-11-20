#!/usr/bin/env bash
# 하루기록 릴리스 서명 (키 순환 v3.1)
# 키와 비밀번호는 저장소 밖에 둡니다. 사용 예:
#   KEYS_DIR=~/daylog-keys ./sign-release.sh
# KEYS_DIR 안에 old.keystore, new.p12, new.pass, old.pass, lineage.bin 이 있어야 합니다.
set -euo pipefail
KEYS_DIR="${KEYS_DIR:?KEYS_DIR 를 지정하세요}"
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0"
IN=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=app/build/outputs/apk/release/DayLog-release.apk
"$BT/zipalign" -c -P 16 4 "$IN" >/dev/null || { "$BT/zipalign" -f -P 16 4 "$IN" "$IN.aligned"; mv "$IN.aligned" "$IN"; }
"$BT/apksigner" sign \
  --ks "$KEYS_DIR/old.keystore" --ks-key-alias daylog --ks-pass "file:$KEYS_DIR/old.pass" \
  --next-signer --ks "$KEYS_DIR/new.p12" --ks-key-alias daylog2 --ks-pass "file:$KEYS_DIR/new.pass" \
  --lineage "$KEYS_DIR/lineage.bin" --rotation-min-sdk-version 33 \
  --out "$OUT" "$IN"
"$BT/apksigner" verify -v --print-certs "$OUT"
sha256sum "$OUT"
