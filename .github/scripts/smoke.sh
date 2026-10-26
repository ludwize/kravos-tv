#!/usr/bin/env bash
# Installs the APK on the emulator and walks the app: phone by tapping visible text, TV with the D-pad.
# Saves screenshots, app log and crashes to smoke-<mode>/.
set -u
MODE=${1:-phone}
OUT=smoke-$MODE
mkdir -p "$OUT"
APK=$(ls out/*.apk | head -1)
PKG=net.kravos.tv

adb install -r "$APK" > "$OUT/install.txt" 2>&1
adb logcat -c
adb shell am start -W -n $PKG/.ui.MainActivity > "$OUT/start.txt" 2>&1
sleep 8

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$OUT/steps.txt"; }
key() { adb shell input keyevent "$1"; sleep "${2:-1}"; }

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
}

# tap the center of the n-th node whose text/content-desc fully matches a regex
tap_re() {
  dump
  python3 - "$1" "${2:-0}" "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
pat, idx, path = re.compile(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
hits = []
for n in root.iter('node'):
    t = n.get('text') or n.get('content-desc') or ''
    if pat.fullmatch(t):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) > idx:
    print(*hits[idx])
PY
  if [ -s "$OUT/tap.txt" ]; then
    adb shell input tap $(cat "$OUT/tap.txt")
    echo "tapped /$1/" >> "$OUT/steps.txt"
  else
    echo "not found: /$1/" >> "$OUT/steps.txt"
  fi
  sleep 2
}

# wait until some text matching the regex is on screen (max N seconds)
wait_re() {
  for _ in $(seq 1 "${2:-60}"); do
    dump
    if python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat, path = re.compile(sys.argv[1]), sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
sys.exit(0 if any(pat.fullmatch(n.get('text') or '') for n in root.iter('node')) else 1)
PY
    then echo "found /$1/" >> "$OUT/steps.txt"; return 0; fi
    sleep 2
  done
  echo "timeout waiting /$1/" >> "$OUT/steps.txt"
  return 1
}

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
scroll() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 400; sleep 2; }

shot 00-start
wait_re '.*(Srbija|Omiljeni|Nedavno).*' 90
sleep 4
shot 01-home

if [ "$MODE" = "phone" ]; then
  tap_re '.*Srbija'; sleep 3; shot 02-serbia
  scroll; shot 03-serbia-scroll
  tap_re 'Nova|RTS 2|IDJTV|Newsmax Balkans|TVCG MNE|Pannon TV'; sleep 25; shot 04-player
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 1; sleep 6; shot 05-player-landscape
  adb shell input tap $((H / 2)) $((W / 2)); sleep 2; shot 06-player-landscape-info
  adb shell settings put system user_rotation 0; sleep 4
  key KEYCODE_BACK 3
  tap_re '.*Ceo region'; sleep 3; shot 07-region
  tap_re 'Vodič'; sleep 4; shot 10-guide
  tap_re 'Pretraga'; sleep 2
  adb shell input text "RTS"; sleep 4; key KEYCODE_BACK 1; shot 11-search
  tap_re 'Podešavanja'; sleep 3; shot 12-settings
  scroll; shot 13-settings-scroll
  tap_re 'Kanali'; sleep 2
  tap_re '.*Nemačka'; sleep 3; shot 14-germany
  tap_re 'Das Erste|ZDF|Arte|DW.*|Euronews.*' ; sleep 20; shot 15-player-de
  key KEYCODE_BACK 3
else
  shot 01-tv-home
  key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; shot 02-tv-focus
  key KEYCODE_DPAD_CENTER 25; shot 03-tv-player
  key KEYCODE_DPAD_UP 20; shot 04-tv-zap
  key KEYCODE_DPAD_CENTER 2; shot 05-tv-info
  key KEYCODE_DPAD_CENTER 2; shot 06-tv-list
  key KEYCODE_BACK 2
  key KEYCODE_DPAD_RIGHT 2; shot 07-tv-options
  key KEYCODE_BACK 2
  key KEYCODE_BACK 3; shot 08-tv-back-home
  key KEYCODE_DPAD_LEFT; key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN 3; shot 09-tv-rail
  key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP
  key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_CENTER 5; shot 10-tv-guide
  key KEYCODE_DPAD_DOWN 2; key KEYCODE_DPAD_RIGHT 2; shot 11-tv-guide-nav
fi

PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid=$PID" > "$OUT/status.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -n -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crash.txt" || true
grep -n -E "KravosTV|AndroidRuntime|ExoPlayer|net.kravos" "$OUT/logcat.txt" | tail -600 > "$OUT/app-log.txt" || true
if [ -s "$OUT/crash.txt" ]; then echo "CRASH" >> "$OUT/status.txt"; fi
rm -f "$OUT/logcat.txt" "$OUT/ui.xml"
cat "$OUT/status.txt" "$OUT/steps.txt"
exit 0
