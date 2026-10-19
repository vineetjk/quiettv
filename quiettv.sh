#!/bin/sh
# QuietTV: a calm, fast Android TV / Google TV, without breaking it.
#
#   sh quiettv.sh scan    <TV_IP>             what's on the TV and what's safe to turn off (changes nothing)
#   sh quiettv.sh clean   <TV_IP>             turn off junk apps, asking about each one
#   sh quiettv.sh tune    <TV_IP>             animations, screensaver, sleep timer and background apps
#   sh quiettv.sh install <TV_IP> [apps]      the QuietTV home screen; apps = "pkg1,pkg2,..." in order
#   sh quiettv.sh status  <TV_IP>             what's changed, what the TV turned back on, free memory
#   sh quiettv.sh reapply <TV_IP>             put back changes the TV undid (some TVs do at every restart)
#   sh quiettv.sh undo    <TV_IP>             put everything back the way it was
#   sh quiettv.sh report  <TV_IP>             details to paste into a GitHub issue if you need help
#
# Every change is asked about first (YES=1 answers yes to everything) and written to a list on the TV,
# so `reapply` and `undo` work from any computer. Apps are turned off, never uninstalled. QuietTV never
# turns off the TV's own home screen: on many Google TVs that crashes Android (see docs/RESEARCH.md).
set -u
cd "$(dirname "$0")" || exit 1

PKG=com.quiettv.home
GUARD=$PKG/$PKG.HomeGuard
JOURNAL=/data/local/tmp/quiettv-journal.txt
REPO=vineetjk/quiettv

HELP="https://github.com/$REPO/issues/new/choose"

die() {
  echo "$*" >&2
  echo "Need help? Run 'sh quiettv.sh report <TV_IP>' and open an issue: $HELP" >&2
  exit 1
}

usage() { sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

connect() {
  case "$1" in
    *.*.*.*:*) TV=$1 ;;
    *.*.*.*)   TV=$1:5555 ;;
    *)         TV=$1 ;;
  esac
  command -v adb >/dev/null 2>&1 || die "adb is missing. Mac: brew install android-platform-tools. Windows/Linux: https://developer.android.com/tools/releases/platform-tools"
  case "$TV" in *:*) adb connect "$TV" >/dev/null 2>&1 || true ;; esac
  state=$(adb -s "$TV" get-state 2>&1 || true)
  case "$state" in
    device) ;;
    *unauthorized*) die "Your TV is asking \"Allow debugging?\". Tick \"Always allow\", press Allow, then run this again." ;;
    *) die "Can't reach $TV. Check the TV is on, on the same network, and has USB/network debugging turned on." ;;
  esac
}

# Runs a command on the TV and prints its output without the \r adb adds.
tv() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

ask() {
  if [ "${YES:-}" = 1 ]; then echo "$1 [y/N] y"; return 0; fi
  printf '%s [y/N] ' "$1"
  read -r answer </dev/tty || return 1
  case "$answer" in y|Y|yes|Yes|YES) return 0 ;; *) return 1 ;; esac
}

note() { tv "echo '$*' >> $JOURNAL"; }

is_off() { tv pm list packages -d --user 0 "$1" | grep -qx "package:$1"; }
is_on() { tv pm list packages -e --user 0 "$1" | grep -qx "package:$1"; }

# True for apps QuietTV must never turn off: the protected list, anything Android always keeps
# running, and anything whose flags can't be read.
protected() {
  [ "$1" = "$PKG" ] && return 0
  while IFS= read -r pat; do
    case "$pat" in ''|'#'*) continue ;; esac
    # shellcheck disable=SC2254
    case "$1" in $pat) return 0 ;; esac
  done < lists/protected.txt
  flags=$(tv dumpsys package "$1" | sed -n '/pkgFlags=\[/{p;q;}')
  [ -n "$flags" ] || return 0
  case "$flags" in *PERSISTENT*) return 0 ;; esac
  return 1
}

free_mb() { tv cat /proc/meminfo | awk '/^MemAvailable:/ {printf "%d MB", $2/1024}'; }

# ------------------------------------------------------------------------------------------ scan

cmd_scan() {
  echo "TV: $(tv getprop ro.product.manufacturer) $(tv getprop ro.product.model), Android $(tv getprop ro.build.version.release)"
  echo "Memory: $(free_mb) free of $(tv cat /proc/meminfo | awk '/^MemTotal:/ {printf "%d MB", $2/1024}')"
  echo "Home button opens: $(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
  echo
  echo "Biggest memory users right now:"
  tv dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p' | sed -n '2,11p'
  echo
  echo "Safe to turn off (from lists/safe.txt):"
  found=0
  while IFS='|' read -r p kind what; do
    p=$(echo "$p" | tr -d ' '); kind=$(echo "$kind" | tr -d ' ')
    case "$p" in ''|'#'*) continue ;; esac
    if is_on "$p"; then printf '  %-8s %-45s %s\n' "$kind" "$p" "$what"; found=1
    elif is_off "$p"; then printf '  %-8s %-45s %s\n' "off" "$p" "(already off)"; fi
  done < lists/safe.txt
  [ $found = 1 ] || echo "  nothing (or everything is already off)"
  echo
  echo "Everything else is left alone. To see all $(tv pm list packages | wc -l | tr -d ' ') packages: adb shell pm list packages"
}

# ------------------------------------------------------------------------------------------ clean

cmd_clean() {
  echo "Free memory before: $(free_mb)"
  changed=0
  while IFS='|' read -r p kind what; do
    p=$(echo "$p" | tr -d ' '); kind=$(echo "$kind" | tr -d ' ')
    case "$p" in ''|'#'*) continue ;; esac
    is_on "$p" || continue
    if protected "$p"; then echo "Leaving $p on: it's protected."; continue; fi
    echo
    echo "$what  ($p)"
    ask "Turn it off?" || continue
    note "off $p"
    tv pm disable-user --user 0 "$p" >/dev/null
    if is_off "$p"; then echo "  turned off"; changed=1; else echo "  couldn't turn it off" >&2; fi
  done < lists/safe.txt
  [ $changed = 1 ] || { echo "Nothing changed."; return; }
  # Android saves app states a few seconds later; a restart before then can lose them.
  sleep 10
  echo
  echo "Free memory now: $(free_mb). Some TVs turn apps back on at restart: run 'sh quiettv.sh reapply <TV_IP>' after one."
}

# ------------------------------------------------------------------------------------------ tune

# setting <namespace> <key> <value> <question>
setting() {
  old=$(tv settings get "$1" "$2")
  [ "$old" = "$3" ] && { echo "Already set: $1 $2 = $3"; return; }
  ask "$4 ($1 $2: $old -> $3)" || return
  note "setting $1 $2 $old $3"
  tv settings put "$1" "$2" "$3"
}

cmd_tune() {
  setting global window_animation_scale 0 "Turn off window animations?"
  setting global transition_animation_scale 0 "Turn off transition animations?"
  setting global animator_duration_scale 0 "Turn off animator animations?"
  setting secure screensaver_enabled 0 "Turn off the screensaver?"
  setting secure screensaver_activate_on_sleep 0 "Don't start the screensaver before sleep?"
  printf 'Sleep after how many minutes without using the remote? (Enter = leave as is) '
  if [ "${YES:-}" = 1 ]; then mins=${SLEEP_MINUTES:-}; echo "$mins"; else read -r mins </dev/tty || mins=; fi
  case "$mins" in
    '') ;;
    *[!0-9]*) echo "Not a number, left as is." ;;
    *) ms=$((mins * 60000))
       setting system screen_off_timeout "$ms" "Turn the screen off after $mins minutes?"
       setting secure sleep_timeout "$ms" "Sleep after $mins minutes?" ;;
  esac
  # Some apps (Netflix especially) start by themselves at boot and sit in memory. On Android 14,
  # "background restricted" apps are closed about a minute after they go idle. They still open normally.
  restrict_bg com.netflix.ninja "Netflix"
  restrict_bg com.amazon.amazonvideo.livingroom "Prime Video"
  restrict_bg com.disney.disneyplus "Disney+"
}

restrict_bg() {
  is_on "$1" || return
  old=$(tv cmd appops get "$1" RUN_ANY_IN_BACKGROUND | sed -n 's/^RUN_ANY_IN_BACKGROUND: \([a-z]*\).*/\1/p')
  [ "$old" = ignore ] && { echo "Already restricted: $2"; return; }
  # TV makers can exempt apps from power saving (Acer does this for Netflix). Then the limit does nothing,
  # and the "except idle" list can't be changed from the shell, so don't offer it.
  if tv dumpsys deviceidle whitelist | grep -q ",$1,"; then
    echo "$2 is exempted from power saving by the TV maker, so a background limit has no effect. Skipping."
    return
  fi
  [ -n "$old" ] || old=default
  mb=$(tv dumpsys meminfo "$1" | awk '/TOTAL PSS:/ {printf "%d", $3/1024; exit}')
  echo
  echo "$2 starts by itself and stays in memory${mb:+ (using $mb MB right now)}."
  ask "Let Android close $2 when you're not using it? It still opens normally, a few seconds slower." || return
  note "bg $1 $old"
  tv cmd appops set "$1" RUN_ANY_IN_BACKGROUND ignore
}

# ------------------------------------------------------------------------------------------ install

# The APK to install: $QUIETTV_APK, your own build (out/), one next to this script (from a release zip),
# or the latest release from GitHub, checked against its published SHA-256.
find_apk() {
  for f in "${QUIETTV_APK:-}" out/quiettv.apk quiettv.apk; do
    [ -n "$f" ] && [ -f "$f" ] && { APK=$f; return; }
  done
  command -v curl >/dev/null 2>&1 || die "curl is needed to download QuietTV."
  dir=$(mktemp -d)
  echo "Downloading the latest QuietTV release..."
  base=https://github.com/$REPO/releases/latest/download
  curl -fsSL -o "$dir/quiettv.apk" "$base/quiettv.apk" && curl -fsSL -o "$dir/quiettv.apk.sha256" "$base/quiettv.apk.sha256" \
    || die "Couldn't download QuietTV from github.com/$REPO/releases. Or build it yourself: sh build.sh"
  want=$(cut -d' ' -f1 < "$dir/quiettv.apk.sha256")
  if command -v sha256sum >/dev/null 2>&1; then got=$(sha256sum "$dir/quiettv.apk" | cut -d' ' -f1)
  else got=$(shasum -a 256 "$dir/quiettv.apk" | cut -d' ' -f1); fi
  [ -n "$want" ] && [ "$want" = "$got" ] || die "The download doesn't match its checksum. Not installing it."
  APK=$dir/quiettv.apk
}

guard_on() { tv settings get secure enabled_accessibility_services | tr ':' '\n' | grep -qx "$GUARD"; }

cmd_install() {
  apps=${1:-}
  find_apk
  installed=0
  tv pm list packages "$PKG" | grep -qx "package:$PKG" && installed=1
  if [ $installed = 0 ]; then
    ask "Install the QuietTV home screen ($(wc -c < "$APK" | tr -d ' ') bytes)?" || return
    note "installed $PKG"
  fi
  echo "Installing... If the TV shows a Google Play Protect message, choose to install anyway."
  if ! out=$(adb -s "$TV" install -r "$APK" 2>&1); then
    case "$out" in
      *UPDATE_INCOMPATIBLE*|*INCONSISTENT_CERTIFICATES*|*SIGNATURES_DO_NOT_MATCH*)
        echo "The QuietTV on the TV was signed with a different key (for example, one you built yourself)."
        ask "Remove it and install this one? Your app list resets and the Home guard is set up again." || return
        adb -s "$TV" uninstall "$PKG" >/dev/null 2>&1
        adb -s "$TV" install "$APK" >/dev/null || die "Install failed." ;;
      *) echo "$out" >&2; die "Install failed." ;;
    esac
  fi
  # Android 13+ can block accessibility for sideloaded apps ("Restricted setting"). adb installs aren't
  # restricted, but allow it anyway in case the app was installed from a file manager.
  tv appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1 || true
  # Started as a home task (MAIN + HOME only), so Android keeps it above the TV's own home screen.
  set -- am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "$PKG/.HomeActivity" --es stamp "$(date +%s)"
  [ -n "$apps" ] && set -- "$@" --es apps "$apps"
  tv "$@" >/dev/null

  if ! guard_on; then
    echo
    echo "QuietTV leaves your TV's own home screen on (turning it off crashes many Google TVs)."
    echo "Instead, a small accessibility service notices when the TV opens its home screen and shows QuietTV."
    if ask "Let QuietTV take over the Home button?"; then
      old=$(tv settings get secure enabled_accessibility_services)
      oldon=$(tv settings get secure accessibility_enabled)
      note "a11y $old $oldon"
      case "$old" in null|'') new=$GUARD ;; *) new="$old:$GUARD" ;; esac
      tv settings put secure enabled_accessibility_services "$new"
      tv settings put secure accessibility_enabled 1
    fi
  fi
  tv "$@" >/dev/null
  sleep 3
  top=$(tv dumpsys activity activities | grep -m1 ResumedActivity)
  case "$top" in
    *"$PKG"*) echo "Done. Press Home on your remote: QuietTV opens." ;;
    *) echo "Installed, but Home still shows: $top"
       echo "Check Settings > Accessibility > QuietTV is on, or run: sh quiettv.sh status $TV" ;;
  esac
}

# ------------------------------------------------------------------------------------------ reapply

# Puts back the changes recorded on the TV that the TV has undone (some TVs turn apps back on at every
# restart). Only re-applies what you already approved; protected apps are still never turned off.
cmd_reapply() {
  list=$(tv "cat $JOURNAL 2>/dev/null")
  [ -n "$list" ] || { echo "No changes recorded on this TV."; return; }
  fixed=0
  printf '%s\n' "$list" > "${TMPDIR:-/tmp}/quiettv-reapply.$$"
  while read -r kind a b c d; do
    case "$kind" in
      off)
        is_on "$a" || continue
        if protected "$a"; then echo "Leaving $a on: it's protected."; continue; fi
        tv pm disable-user --user 0 "$a" >/dev/null
        if is_off "$a"; then echo "Turned $a off again"; fixed=$((fixed + 1)); fi ;;
      setting)
        [ -n "$d" ] || continue
        [ "$(tv settings get "$a" "$b")" = "$d" ] && continue
        tv settings put "$a" "$b" "$d"; echo "Set $a $b back to $d"; fixed=$((fixed + 1)) ;;
      bg)
        tv cmd appops get "$a" RUN_ANY_IN_BACKGROUND | grep -q ignore && continue
        tv cmd appops set "$a" RUN_ANY_IN_BACKGROUND ignore; echo "Restricted $a in the background again"; fixed=$((fixed + 1)) ;;
      a11y)
        guard_on && continue
        old=$(tv settings get secure enabled_accessibility_services)
        case "$old" in null|'') new=$GUARD ;; *) new="$old:$GUARD" ;; esac
        tv settings put secure enabled_accessibility_services "$new"
        tv settings put secure accessibility_enabled 1
        echo "Turned the Home button guard back on"; fixed=$((fixed + 1)) ;;
    esac
  done < "${TMPDIR:-/tmp}/quiettv-reapply.$$"
  rm -f "${TMPDIR:-/tmp}/quiettv-reapply.$$"
  if [ $fixed = 0 ]; then echo "Everything is still in place."; return; fi
  sleep 10 # Android saves app states a few seconds later
  echo "Put back $fixed change(s). Free memory: $(free_mb)"
}

# ------------------------------------------------------------------------------------------ status

cmd_status() {
  echo "Free memory: $(free_mb)"
  if tv pm list packages "$PKG" | grep -qx "package:$PKG"; then
    echo "QuietTV: installed, version $(tv dumpsys package "$PKG" | sed -n 's/.*versionName=//p' | head -1)"
  else echo "QuietTV: not installed"; fi
  if guard_on; then echo "Home button guard: on (Home opens QuietTV)"; else echo "Home button guard: off (Home opens the TV's own home screen)"; fi
  echo "TV's own home screen (left on): $(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
  echo
  list=$(tv "cat $JOURNAL 2>/dev/null")
  [ -n "$list" ] || { echo "No changes recorded on this TV."; return; }
  echo "Changes recorded on the TV:"
  printf '%s\n' "$list" | while read -r kind a b c d; do
    case "$kind" in
      off) if is_off "$a"; then echo "  ok      $a is off"
           else echo "  BACK ON $a ($(tv dumpsys package "$a" | grep -m1 -o 'lastDisabledCaller: .*'))"; fi ;;
      setting) now=$(tv settings get "$a" "$b")
               if [ -z "$d" ] || [ "$now" = "$d" ]; then echo "  ok      $a $b = $now (was $c)"
               else echo "  CHANGED $a $b = $now (should be $d)"; fi ;;
      bg) if tv cmd appops get "$a" RUN_ANY_IN_BACKGROUND | grep -q ignore; then echo "  ok      $a is restricted in the background"
          else echo "  CHANGED $a may run in the background again"; fi ;;
      installed) echo "  ok      $a installed" ;;
    esac
  done
  echo
  echo "If anything says BACK ON or CHANGED: sh quiettv.sh reapply $TV"
}

# ------------------------------------------------------------------------------------------ undo

cmd_undo() {
  list=$(tv "if [ -e $JOURNAL ]; then cat $JOURNAL; fi; echo END")
  case "$list" in *END) ;; *) die "Couldn't read the list of changes on the TV." ;; esac
  list=$(printf '%s\n' "$list" | grep -vx END)
  [ -n "$list" ] || { echo "Nothing to undo on this TV."; return; }
  echo "This puts back everything QuietTV changed on this TV:"
  printf '%s\n' "$list" | sed 's/^/  /'
  ask "Undo all of it?" || return
  failed=0
  # Newest first, so each setting gets the value it had before QuietTV's first change.
  printf '%s\n' "$list" | awk '{a[NR]=$0} END {for (i=NR; i>0; i--) print a[i]}' > "${TMPDIR:-/tmp}/quiettv-undo.$$"
  while read -r kind a b c d; do
    case "$kind" in
      bg) tv cmd appops set "$a" RUN_ANY_IN_BACKGROUND "$b"; echo "$a may run in the background again" ;;
      off) tv pm enable "$a" >/dev/null; if is_off "$a"; then echo "Couldn't turn $a back on" >&2; failed=1; else echo "Turned $a back on"; fi ;;
      setting) if [ "$c" = null ]; then tv settings delete "$a" "$b" >/dev/null; else tv settings put "$a" "$b" "$c"; fi
               echo "Put back $a $b = $c" ;;
      a11y) if [ "$a" = null ]; then tv settings delete secure enabled_accessibility_services >/dev/null
            else tv settings put secure enabled_accessibility_services "$a"; fi
            [ "$b" = null ] || tv settings put secure accessibility_enabled "$b"
            echo "Turned off the Home button guard" ;;
      installed) adb -s "$TV" uninstall "$a" >/dev/null 2>&1 && echo "Removed $a" ;;
      keeper) ;;
    esac
  done < "${TMPDIR:-/tmp}/quiettv-undo.$$"
  rm -f "${TMPDIR:-/tmp}/quiettv-undo.$$"
  [ $failed = 0 ] || die "Some apps stayed off. Run undo again, or: adb shell pm enable <package>"
  tv rm -f "$JOURNAL"
  tv input keyevent KEYCODE_HOME
  echo "Done. Your TV is back the way it was."
}

# ------------------------------------------------------------------------------------------ report

# Everything a helper needs to understand a problem, as Markdown for a GitHub issue. Leaves out the
# TV's IP address and anything personal; the list of apps it shows is only what QuietTV turned off.
cmd_report() {
  f=quiettv-report.txt
  {
    echo "### QuietTV report"
    echo
    echo '```'
    echo "date:        $(date -u '+%Y-%m-%d %H:%M UTC')"
    echo "computer:    $(uname -s) $(uname -m), adb $(adb version | sed -n 's/.*version \([0-9.]*\).*/\1/p' | head -1)"
    echo "tv:          $(tv getprop ro.product.manufacturer) $(tv getprop ro.product.model) ($(tv getprop ro.product.device))"
    echo "android:     $(tv getprop ro.build.version.release) (SDK $(tv getprop ro.build.version.sdk)), build $(tv getprop ro.build.id)"
    echo "memory:      $(free_mb) free of $(tv cat /proc/meminfo | awk '/^MemTotal:/ {printf "%d MB", $2/1024}')"
    v=$(tv dumpsys package "$PKG" | sed -n 's/.*versionName=//p' | head -1)
    echo "quiettv:     ${v:-not installed}"
    if guard_on; then echo "home guard:  on"; else echo "home guard:  off"; fi
    echo "tv home:     $(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
    echo "on screen:   $(tv dumpsys activity activities | grep -m1 ResumedActivity | grep -oE 'u0 [^ ]+' | cut -c4-)"
    echo "a11y:        $(tv settings get secure enabled_accessibility_services)"
    echo "crashes:     $(tv logcat -d -b crash | grep -c 'FATAL EXCEPTION IN SYSTEM') system crash(es) in the log since boot"
    echo '```'
    echo
    echo "**Changes recorded by QuietTV:**"
    echo
    echo '```'
    tv "cat $JOURNAL 2>/dev/null" | while read -r kind a b c d; do
      case "$kind" in
        off) if is_off "$a"; then echo "off      $a"; else echo "BACK ON  $a  ($(tv dumpsys package "$a" | grep -m1 -o 'lastDisabledCaller: .*'))"; fi ;;
        setting) echo "setting  $a $b = $(tv settings get "$a" "$b") (want ${d:-?}, was $c)" ;;
        bg) echo "bg       $a $(tv cmd appops get "$a" RUN_ANY_IN_BACKGROUND | head -1)" ;;
        *) echo "$kind $a" ;;
      esac
    done
    echo '```'
    echo
    echo "**Last QuietTV log lines:**"
    echo
    echo '```'
    tv logcat -d -s QuietTV | grep -v beginning | tail -15 | cut -c1-160
    echo '```'
  } > "$f"
  cat "$f"
  echo
  echo "Saved to $(pwd)/$f. Paste it into an issue: $HELP"
}

# ------------------------------------------------------------------------------------------ main

[ $# -ge 2 ] || usage
command=$1; connect "$2"; shift 2
case "$command" in
  scan) cmd_scan ;;
  clean) cmd_clean ;;
  tune) cmd_tune ;;
  install) cmd_install "${1:-}" ;;
  reapply) cmd_reapply ;;
  status) cmd_status ;;
  undo) cmd_undo ;;
  report) cmd_report ;;
  *) usage ;;
esac
