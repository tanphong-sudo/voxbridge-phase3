#!/system/bin/sh
# Runs on the phone: drives the demo scenario with no adb round trips, so there is no idle time.
D=/sdcard/Android/data/com.voxbridge/files/demo
T=/sdcard/demo-timeline.txt; : > $T
mark() { echo "$(date +%s%3N) $*" >> $T; }
outs() { ls $D/out 2>/dev/null | wc -l; }
dir=vi
isdir() { # 0 when the hold button speaks the given language
  uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if grep -q 'content-desc="Giữ để nói. Nói tiếng Việt"' /sdcard/ui.xml; then cur=vi; else cur=en; fi
  [ "$cur" = "$1" ]
}
setdir() {
  [ "$1" = "$dir" ] && return
  mark "swap to $1"; input tap 540 317; sleep 2.5
  n=0; while ! isdir $1 && [ $n -lt 4 ]; do mark "swap retry"; input tap 540 317; sleep 2.5; n=$((n+1)); done
  dir=$1; sleep 1
}
waitspeech() { # until every output chunk since $1 has finished playing (awk: 13-digit times overflow sh)
  i=0
  while [ $i -lt 120 ]; do
    last=$(cd $D/out 2>/dev/null && stat -c "%n %s" *.wav 2>/dev/null | awk -v since=$1 '{t=substr($1,1,length($1)-4)+0; if (t>=since) {g=1; e=t+($2-44)/44; if (e>m) m=e}} END {if (g) printf "%.0f", m; else print 0}')
    now=$(date +%s%3N)
    if [ "$last" != 0 ]; then echo "$now $last" | awk '{exit !($1 > $2 + 900)}' && return 0
    elif [ $i -gt 80 ]; then return 1; fi
    sleep 0.1; i=$((i+1))
  done
  return 0
}
turn() { # clip lang
  setdir $2
  size=$(stat -c %s $D/clips/$1.wav); ms=$(( (size - 44) / 32 + 300 ))
  n=0
  while :; do
    mark "hold $1 ${ms}ms"; input swipe 540 1974 540 1974 $ms; rel=$(date +%s%3N); mark "release $1"
    logcat -d -s VoxDemo | grep -q "clip $1.wav" && break
    n=$((n+1)); [ $n -ge 3 ] && break
    mark "press ignored, retry $1"; sleep 2
  done
  if waitspeech $rel; then mark "spoken $1"; sleep 0.8; else mark "no audio $1"; sleep 2; fi
}
quick() { # x y name
  mark "quick $3"; q=$(date +%s%3N); input tap $1 $2; waitspeech $q; sleep 0.6
}
rm -f /sdcard/demo-v*.mp4 /sdcard/demo-v*.start
( for i in 1 2; do date +%s%3N > /sdcard/demo-v$i.start; screenrecord --bit-rate 8000000 --time-limit 180 /sdcard/demo-v$i.mp4; done ) &
sleep 1.5; mark start
cmd statusbar expand-settings; sleep 1.5
mark "airplane on"; cmd connectivity airplane-mode enable; sleep 2.5
cmd statusbar collapse; sleep 1.5
isdir vi || { input tap 540 317; sleep 2.5; }
turn 01-vi vi
turn 02-en en
turn 03-vi vi
turn 04-en en
turn 05-vi vi
turn 06-vi vi
turn 07-en en
turn 08-vi vi
turn 09-en en
turn 10-vi vi
turn 11a-vi-noisy vi
turn 11b-vi vi
quick 288 1416 stop
quick 792 1605 ambulance
turn 13-en en
turn 14-vi vi
sleep 1; mark end
pkill -INT screenrecord; sleep 2
cmd connectivity airplane-mode disable
mark done
