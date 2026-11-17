#!/bin/bash
# Drive one case on a test phone over plain adb (no root): the same protocol
# suite.sh uses, reading the case directory with run-as (debuggable build).
#   [SCREEN_OFF=1] [CASE=main] tools/drive.sh SCENARIO [SECONDS] [extra am args...]
set -uo pipefail
PKG=org.neutrino.afdo.workload
sc=$1; secs=${2:-20}; shift $(( $# >= 2 ? 2 : 1 ))
case=${CASE:-main}
run=dev$(date +%H%M%S); key=$sc.$case.1.1
state() { adb shell run-as $PKG cat files/cases/$run/$key/state 2>/dev/null | tr -d '\r'; }
wait_for() { # want timeout
	local i=0 s
	while [ $i -lt $2 ]; do
		s=$(state)
		[ "$s" = "$1" ] && return 0
		case "$s" in FAILED|SAFETY) echo "state $s"; return 1;; esac
		adb shell run-as $PKG test -f files/cases/$run/$key/safety && { echo "safety stop"; return 1; }
		sleep 1; i=$((i + 1))
	done
	echo "timeout waiting for $1 (state ${s:-none})"; return 1
}
adb shell am force-stop $PKG
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/.Run --es run $run --es scenario $sc --es case $case --ei rep 1 --ei attempt 1 \
	--ei seconds $secs --el seed 12345 --ei protocol 1 "$@" >/dev/null
t0=$(date +%s)
wait_for READY 90 && echo "READY after $(( $(date +%s) - t0 )) s" &&
	{ [ "${SCREEN_OFF:-0}" != 1 ] || { adb shell input keyevent KEYCODE_SLEEP; sleep 3; echo "screen off"; }; } &&
	adb shell am broadcast -n $PKG/.Ctl -a $PKG.RUN --es run $run --es key $key >/dev/null &&
	wait_for DONE $((secs + 60)) && echo "DONE after $(( $(date +%s) - t0 )) s" &&
	{ [ "${SCREEN_OFF:-0}" != 1 ] || adb shell input keyevent KEYCODE_WAKEUP; } &&
	adb shell am broadcast -n $PKG/.Ctl -a $PKG.VALIDATE --es run $run --es key $key >/dev/null &&
	wait_for VALIDATED 120
adb shell run-as $PKG cat files/cases/$run/$key/result.json 2>/dev/null
adb shell am force-stop $PKG
