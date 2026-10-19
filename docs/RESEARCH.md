# What we learned (and where it's from)

QuietTV started on an Acer Google TV (model R3G, `ACER/ACER/R3_GTV:14/UKR1.260612`, Realtek, 1.5 GB RAM)
where the usual "turn off the stock launcher" approach crashed Android. This page explains why QuietTV
works the way it does. AOSP links are to `android14-release`.

## 1. Don't turn off the Google TV home screen

On this TV, turning off `com.google.android.apps.tv.launcherx` (Google TV's home screen) let Google TV's
setup app take over Home: `com.google.android.tungsten.setupwraith/.RecoveryActivity` has a HOME
intent filter with priority 1. That activity keeps restarting while it tries to turn the launcher back
on, and `system_server` crashed in the window manager about once a minute
(`IllegalArgumentException: positionChildAt … Task.moveTaskToBack`). Turning the setup app off too
"fixed" it until the next restart, when Acer's Settings app turned the setup app back on
(`lastDisabledCaller: com.android.tv.settings`) and the crashes came back.

Other people have hit the same wall. Reports and proposed fixes, all on the tv-detox repo:
- [tv-detox#14](https://github.com/Ansub/tv-detox/issues/14): Xiaomi
- [tv-detox#16](https://github.com/Ansub/tv-detox/issues/16): TCL
- [tv-detox#17](https://github.com/Ansub/tv-detox/pull/17): a proposed fix
- [tv-detox#21](https://github.com/Ansub/tv-detox/issues/21): the Acer report from this project

Making another app the default home doesn't work either. `ResolveIntentHelper.chooseBestActivity`
returns the highest-priority match before it even looks at the preferred activity. Intent-filter
priorities of apps that aren't privileged are capped at 0 (`ComponentResolver.adjustPriority`).

**So QuietTV never turns off the TV's home screen or setup app.** `lists/protected.txt` enforces this.

## 2. Catch the Home button instead

Every working open-source example keeps the stock launcher on and uses an accessibility service:
- [LtvLauncher](https://github.com/LeanBitLab/LtvLauncher)
- [FTVLaunchX](https://github.com/codefaktor/FTVLaunchX)
- [LauncherHijack](https://github.com/BaronKiko/LauncherHijack)

Projectivy Launcher does the same, according to its developer.

- **`KEYCODE_HOME` reaches accessibility services before Android acts on it.** `InputDispatcher::notifyKey`
  runs `interceptKeyBeforeQueueing`, then the accessibility `filterInputEvent`, and only then dispatches.
  `PhoneWindowManager` handles Home later, in `interceptKeyBeforeDispatching`. The service needs both
  `canRequestFilterKeyEvents="true"` and `flagRequestFilterKeyEvents`. Every key waits on the service
  (500 ms timeout), so QuietTV's `onKeyEvent` only checks the key code.
- **Key events injected with `adb shell input` skip the accessibility filter.** Only real remote presses
  get caught. That's why QuietTV also watches for the stock home window (`TYPE_WINDOW_STATE_CHANGED`) as
  a fallback.
- **No flash of the stock home when an app closes.** If QuietTV is started with `ACTION_MAIN` plus *only*
  `CATEGORY_HOME`, Android gives it activity type `home` and keeps it above the stock launcher. Going Back
  out of an app then returns to QuietTV directly. This comes from `ActivityRecord.setActivityType` and
  `RootWindowContainer.resumeHomeActivity`, and is discussed in
  [miproja1#605](https://github.com/spocky/miproja1/issues/605). Verified on the Acer: QuietTV's task shows
  `type=home` above `launcherx`.
- **Android 14 "restricted settings".** These block accessibility services for apps installed from
  downloaded files. `adb install` counts as `PACKAGE_SOURCE_OTHER`, so it isn't restricted.
  `quiettv.sh install` still runs `appops set … ACCESS_RESTRICTED_SETTINGS allow`, just in case.
- **Force-stopping the app removes its accessibility service from the setting**
  (`AccessibilityManagerService.onHandleForceStop`). Don't use "Force stop" or cleaner apps on QuietTV.
  `sh quiettv.sh status` shows whether the guard is on, and `reapply` turns it back on.
- **An update kills the app process.** QuietTV remembers whether it was on screen and brings itself back
  when the guard restarts. This was tested on the Acer.

## 3. Some TVs undo your changes at every restart

On the Acer, the vendor Settings app re-enabled 15 `disable-user`'d packages at every boot, including the
setup app. It left a few alone (S-Channel, Wi-Fi Hotspot, the live wallpaper picker, and launcherx
itself). `sh quiettv.sh status` shows which changes were undone and by whom, and
`sh quiettv.sh reapply` puts them back from your computer.

An on-TV version that would do this automatically is designed in `proposals/on-tv-keeper/`, but it is
deliberately **not** built. It would connect to the TV's own adbd on 127.0.0.1:5555 with an approved key,
which means a permanent shell credential living on the TV. The research behind it:
- AOSP `packages/modules/adb` `protocol.txt`, `daemon/auth.cpp`, `transport.cpp`
- Shizuku's `AdbClient.kt`
- cgutman's AdbLib

## 4. What actually saves memory on Android 14

| Tweak | Verdict | Source |
|---|---|---|
| Animations off (`window_/transition_/animator_duration_scale 0`) | Real, free | everyday Android setting |
| Screensaver off | Real (the Ambient Mode screensaver is a whole app) | |
| `appops set <app> RUN_ANY_IN_BACKGROUND ignore` | **The one that works.** Android 14 kills background-restricted apps once they're cached and idle (`kill_bg_restricted_cached_idle`, 60 s settle), and stops their foreground services and jobs. The app still opens normally. | `ProcessList.killAppIfBgRestrictedAndCachedIdleLocked`, `ActiveServices` |
| `appops RUN_IN_BACKGROUND ignore` | No effect on apps targeting API 26+ | `appRestrictedInBackgroundLOSP` |
| `am set-standby-bucket` / `am set-inactive` | No effect when app standby is off. That's the norm on TVs: `config_enableAutoPowerModes=false`, and they're always "charging". | `AppStandbyController` |
| `device_config … max_cached_processes` | Not enforced for 10 minutes after boot, can be wiped by server config sync, mostly causes more cold starts | `ActivityManagerConstants`, `OomAdjuster` |
| "Background process limit" developer option | Not persistent (resets at reboot) | TvSettings `DevelopmentFragment` |
| `pm compile -m speed -f` | Mostly folklore: bigger files, no class-loading optimisation | source.android.com ART docs |
| `pm bg-dexopt-job` once | Helps only if apps are stuck at `verify` (check with `pm art dump <pkg>`) | ART Service README |
| Google TV "Apps only mode" | Official. Removes recommendation rows but also Search and Assistant. Manual toggle only. | support.google.com/googletv/answer/10070784 |

On the Acer, Netflix starts by itself at boot through its own `NetflixService` and sits at ~120–150 MB.
**It can't be limited there.** Acer puts Netflix on the system power-save allowlist twice: `system` and
`system-excidle` (see `dumpsys deviceidle whitelist`). That exempts it from background restrictions.
`cmd deviceidle sys-whitelist -com.netflix.ninja` removes only the first entry, and Netflix stayed
in memory for over 2 minutes with the background limit on. Both changes were reverted. `quiettv.sh tune`
now skips apps the TV maker has exempted. Prime Video isn't exempt, so its limit works.

Turning off the junk apps took free memory from 563 MB to 615 MB.

## 5. Testing gotchas we hit

- `adb shell input keyevent` presses don't go through accessibility services. Test Home with the real
  remote.
- If the TV has a short sleep timer, it falls asleep during long tests. Apps launched with the screen off
  are stopped straight away, and Netflix then exits on its own. Wake it first: `adb shell input keyevent KEYCODE_WAKEUP`.
- `sendevent` to `/dev/input/*` is blocked for the shell on user builds.
