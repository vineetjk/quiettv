# QuietTV

**A calm, fast home screen for Android TV and Google TV that doesn't break your TV.**

<img src="docs/home-screen.png" alt="The QuietTV home screen: a clock, your apps, HDMI inputs and a few shortcuts" width="100%">

QuietTV is two things:

- **A tiny home screen** (a 29 KB app using 30–50 MB of RAM; Google TV's own home screen used ~130 MB on the same TV). It shows a clock, your apps, your HDMI inputs, Settings and nothing else. No ads, no rows, no autoplay trailers, no account.
- **A careful cleanup script** (`quiettv.sh`). It turns off junk apps and slow settings, asks before every change, and writes each change to a list on the TV so it can be undone from any computer.

## Why another one?

Most debloat guides and home-screen replacements turn off Google TV's own home screen
(`com.google.android.apps.tv.launcherx`). On many Google TVs that's dangerous. Google TV's setup app then
takes over the Home button and loops, and on some TVs Android itself crashes again and again. This has
been seen on Acer, TCL and Xiaomi; details are in [docs/RESEARCH.md](docs/RESEARCH.md).

QuietTV **never turns off the TV's home screen or setup app**. Instead, a small accessibility service
catches the Home button and opens QuietTV, the same technique Projectivy Launcher and other open-source
launchers use. QuietTV runs as a "home" task, so leaving an app comes straight back to it. If anything
goes wrong, the TV's own home screen is still there.

## What you need

- An Android TV or Google TV (tested on Google TV 14) with **Developer options**, **USB debugging** and **Network debugging** turned on.
  To get there: Settings → System → About → press OK on **Build** 7 times.
- A computer on the same network with [`adb`](https://developer.android.com/tools/releases/platform-tools). On a Mac: `brew install android-platform-tools`.
- To build the app: Java 17 and the Android SDK (platform 34 and build-tools 34). On a Mac:
  `brew install openjdk@17 && brew install --cask android-commandlinetools && sdkmanager "platforms;android-34" "build-tools;34.0.0"`

## Use it

```sh
sh quiettv.sh scan    192.168.1.50     # what's on the TV, what's safe to turn off, memory. Changes nothing.
sh quiettv.sh clean   192.168.1.50     # turn off junk apps from lists/safe.txt, asking about each one
sh quiettv.sh tune    192.168.1.50     # animations off, screensaver off, sleep timer, background limits
sh quiettv.sh install 192.168.1.50 "com.google.android.youtube.tv,com.netflix.ninja"   # the home screen
sh quiettv.sh status  192.168.1.50     # what changed, what the TV turned back on, free memory
sh quiettv.sh reapply 192.168.1.50     # put back changes the TV undid (some TVs do this at every restart)
sh quiettv.sh undo    192.168.1.50     # put everything back the way it was
```

The first time, the TV asks **"Allow debugging?"**. Tick **Always allow**, then press **Allow**.

Every change is asked about first. Set `YES=1` to answer yes to everything. Every change is also written to
`/data/local/tmp/quiettv-journal.txt` on the TV before it's made, so `undo`, `status` and `reapply`
work from any computer.

On the TV, hold **OK** (or press **Menu**) on an app to move it, remove it from the home screen, or
open its App info. **Add app** puts any app on the home screen. **Original home** opens the TV's own home
screen once; pressing Home brings you back.

## Safety rules

- Apps are **turned off, never uninstalled**. `adb shell pm enable <package>` brings any of them back.
- `lists/protected.txt` is never touched. It covers Google Play services, Play Store, Settings,
  System UI, home screens and setup apps, keyboards, the remote and Bluetooth, HDMI and tuner inputs,
  power, updates, and anything Android keeps running all the time.
- `lists/safe.txt` only lists apps that have been turned off without problems on a named TV. To add one,
  say which TV you tested it on.
- The Home guard only sees the Home key and which app's window opened. It never reads what's on screen
  or anything you type. Its source is `app/src/com/quiettv/home/HomeGuard.java`, under 100 lines.

## Things to know

- **Don't "Force stop" QuietTV or clean it with a cleaner app.** On Android, force-stopping an app
  switches its accessibility service off. Home then opens the TV's own home screen until you run
  `sh quiettv.sh reapply <TV_IP>`, or turn QuietTV back on in Settings → Accessibility.
- **Some TVs turn apps back on at every restart.** The Acer this was built on does it through its Settings
  app. Run `sh quiettv.sh status` after a restart to see which ones came back, then `sh quiettv.sh reapply`
  to turn them off again.
- **Remote buttons tied to the TV's own home screen** (like Guide) should keep working, since it stays on. QuietTV only catches Home. Not yet tested on every remote.
- **Background limits:** `tune` offers to stop Prime Video and similar apps from sitting in memory when
  you're not using them. Apps the TV maker has exempted from power saving can't be limited, which is the
  case for Netflix on Acer. `tune` checks for this and skips them.
- **Google TV's "Apps only mode"** (Settings → Accounts & Sign In → your profile) also removes
  recommendation rows, but it turns off Search and the Assistant too. It can't be set from adb.

## Tested on

| TV | Android | Result |
|---|---|---|
| Acer R3G (`R3_GTV`, Realtek, 1.5 GB) | Google TV 14 | Home guard works, including after restarts and app updates. The TV re-enables junk apps at boot: use `reapply`. Netflix is exempted from power saving. |

Please add your TV with a pull request, including what worked and what didn't.

## How it works

- `app/`: the home screen in plain Java. No libraries and no Gradle; `sh build.sh` builds `out/quiettv.apk`.
  - `HomeActivity`: the home screen.
  - `AppsActivity`: all apps, and the app picker for **Add app**.
  - `HomeGuard`: the accessibility service.
- `quiettv.sh`: the script, in POSIX `sh`. It runs on macOS and Linux, and on Windows with Git Bash.
- `lists/`: what's protected and what's safe to turn off.
- `docs/RESEARCH.md`: why it works this way, with sources from the Android source code.
- `proposals/on-tv-keeper/`: a design for re-applying changes from the TV itself. It's deliberately not
  built, for security reasons that its README explains.

## Credits

Inspired by [tv-detox](https://github.com/Ansub/tv-detox), and built after its approach crashed a TV
([tv-detox#21](https://github.com/Ansub/tv-detox/issues/21)). The Home-button technique comes from
[LtvLauncher](https://github.com/LeanBitLab/LtvLauncher), [FTVLaunchX](https://github.com/codefaktor/FTVLaunchX),
[LauncherHijack](https://github.com/BaronKiko/LauncherHijack) and the
[Projectivy Launcher](https://github.com/spocky/miproja1) issue tracker.

This is what worked for us, not professional advice. You're responsible for your own TV.

MIT License.
