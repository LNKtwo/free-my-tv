#!/system/bin/sh
# Amazon's home screen is starting: straight to Free My TV. adb's shell may start it during Android's 5 seconds after Home.
P=/data/local/tmp/homekey.pid
[ -f $P ] && kill -0 "$(cat $P)" 2>/dev/null && exit 0
echo $$ > $P
logcat -v brief -T 1 -b system ActivityManager:I '*:S' | while read line; do
  case "$line" in
    *"START u0"*category.HOME*com.amazon.tv.launcher*) am start -n io.freemytv/.HomeActivity --ez io.freemytv.FROM_HOME true >/dev/null 2>&1 ;;
  esac
done
