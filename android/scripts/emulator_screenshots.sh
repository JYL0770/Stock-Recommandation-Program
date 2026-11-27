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
applog() {  # 앱 프로세스 로그 + 시스템의 앱 관련 로그
  adb logcat -d | grep -iE "stockrec|FATAL|AndroidRuntime|ANR in|lowmemory|Fatal signal|am_kill|am_proc_died|beginning of crash" > "$OUT/$1.txt" || true
  adb shell dumpsys activity activities | grep -iE "ResumedActivity|topResumed" >> "$OUT/$1.txt" || true
}

# 1) 실시간 모드(기본): 네이버 시세를 실제로 받아 추천
launch
shot 01a_live_5s 5
shot 01b_live_20s 15
shot 01c_live_45s 25
shot 01d_live_90s 45
applog logcat_live
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 01e_live_scrolled 2

# 2) 데모 모드 화면들
launch --ez demo true;                                   shot 02_demo_recommend 10
launch --ez demo true --es open 900610;                  shot 03_demo_detail 10
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/5)) 400; shot 04_demo_detail_scrolled 3
launch --ez demo true --es tab backtest --ez run_backtest true; shot 05_demo_backtest 15
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400; shot 05b_demo_backtest_scrolled 3
launch --ez demo true --es tab settings;                 shot 06_settings 6
applog logcat_demo
ls -la "$OUT"
