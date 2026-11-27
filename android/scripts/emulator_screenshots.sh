#!/usr/bin/env bash
# CI 에뮬레이터에서 앱을 실행하고 화면을 캡처 (android.yml 에서 사용)
set -x
APK="$1"; OUT="${2:-shots}"
mkdir -p "$OUT"
PKG=com.stockrec.app
ACT=$PKG/.MainActivity

adb install -r "$APK"
shot() { sleep "$2"; adb exec-out screencap -p > "$OUT/$1.png"; }
launch() { adb shell am force-stop $PKG; adb shell am start -W -n $ACT "$@"; }

# 1) 실시간 모드(기본): 네이버 시세를 실제로 받아 추천
launch
for i in $(seq 1 24); do sleep 5; adb exec-out screencap -p > "$OUT/01_live_recommend.png"; done
adb logcat -d -t 300 | grep -iE "stockrec|AndroidRuntime|FATAL" > "$OUT/logcat_live.txt" || true

# 2) 데모 모드 화면들
launch --ez demo true;                                   shot 02_demo_recommend 12
launch --ez demo true --es open 900610;                  shot 03_demo_detail 12
adb shell input swipe 500 1800 500 500 400;              shot 04_demo_detail_scrolled 3
launch --ez demo true --es tab backtest --ez run_backtest true; shot 05_demo_backtest 20
launch --ez demo true --es tab settings;                 shot 06_settings 8
adb logcat -d | grep -E "AndroidRuntime|FATAL" > "$OUT/crashes.txt" || true
ls -la "$OUT"
