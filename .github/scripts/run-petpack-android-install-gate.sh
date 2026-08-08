#!/bin/sh

set -eu

# android-emulator-runner already waits for boot. Require one successful shell
# round trip as well so a transient ADB reconnect cannot reach the release gate.
adb -s emulator-5554 wait-for-device
ready=0
attempt=1
while [ "$attempt" -le 30 ]; do
  boot_completed="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$boot_completed" = "1" ] && adb -s emulator-5554 shell true >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
  attempt=$((attempt + 1))
done

if [ "$ready" -ne 1 ]; then
  echo "Android emulator did not reach a stable ADB-ready state." >&2
  adb devices -l >&2 || true
  exit 1
fi

SWEETPET_REQUIRE_CAMPUS_BUNDLE=1 python scripts/test_bundled_pack_assets.py

cd petpack/PetPack-v2
python tools/petpack.py publish packs/jk-beach-summer \
  --output "$RUNNER_TEMP/jk-beach-summer-1.0.0.petpack" \
  --reports "$RUNNER_TEMP/jk-beach-report" \
  --allow-warnings \
  --android-project ../../android/SweetGirlfriendPetAndroid \
  --serial emulator-5554

python tools/petpack.py publish packs/nju-campus-girlfriend \
  --output "$RUNNER_TEMP/nju-campus-girlfriend-1.0.0.petpack" \
  --reports "$RUNNER_TEMP/nju-campus-girlfriend-report" \
  --android-project ../../android/SweetGirlfriendPetAndroid \
  --serial emulator-5554
