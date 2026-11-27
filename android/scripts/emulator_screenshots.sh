#!/usr/bin/env bash
# CI 에뮬레이터에서 앱을 실행하고 화면을 캡처 (android.yml 에서 사용)
set -x
APK="$1"; OUT="${2:-shots}"
mkdir -p "$OUT"
PKG=com.stockrec.app
ACT=$PKG/.MainActivity

adb install -r "$APK"
read -r W H < <(adb shell wm size | tail -1 | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
cap() { adb exec-out screencap -p > "$OUT/$1.png"; }
shot() { sleep "$2"; cap "$1"; }
launch() { adb shell am force-stop $PKG; adb logcat -c; adb shell am start -W -n $ACT "$@"; }
swipe() { adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/5)) 400; }
applog() {
  adb logcat -d | grep -iE "stockrec|FATAL|AndroidRuntime|ANR in|lowmemory|Fatal signal|am_kill|am_proc_died|beginning of crash" > "$OUT/$1.txt" || true
  adb shell dumpsys activity activities | grep -iE "ResumedActivity|topResumed" >> "$OUT/$1.txt" || true
}

# 1) 국내 실시간
launch --es region kr
shot 01a_kr_live_20s 20
shot 01b_kr_live_60s 40
applog logcat_kr
swipe; shot 01c_kr_scrolled 2
launch --es region kr --es open TREND_FIRST;            shot 02_kr_stock_detail 12

# 2) 해외 실시간 (미국·핑크시트)
launch --es region us
shot 03a_us_live_30s 30
shot 03b_us_live_90s 60
applog logcat_us
swipe; shot 03c_us_scrolled 2
launch --es region us --es open TREND_FIRST;            shot 04_us_stock_detail 12
swipe; shot 04b_us_stock_detail_scrolled 2
launch --es region us --es open 'IDX:^GSPC';            shot 05_us_index_sp500 12
launch --es region us --es tab settings;                shot 06_us_settings 6

# 3) 데모
launch --es region us --ez demo true;                   shot 07_us_demo 15
launch --es region kr --ez demo true;                   shot 08_kr_demo 15
applog logcat_demo
ls -la "$OUT"
