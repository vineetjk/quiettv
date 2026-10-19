# Set up QuietTV with an AI coding assistant

If you use an AI coding assistant that can run commands on your computer (Claude Code, Codex, Gemini CLI,
Cursor's agent, and so on), copy the prompt below into it and fill in the two `<...>` parts. It walks you
through everything, asks before each change, and keeps your TV safe.

No AI assistant? Follow the [3-step install in the README](README.md#install) instead. It's the same steps.

## The prompt

```text
Set up QuietTV on my Android TV / Google TV: https://github.com/vineetjk/quiettv
My TV is a <brand and model>. Its IP address is <IP address, or "I don't know">.

Work in small steps and explain each one in plain, non-technical words.

1. Check my computer has adb (install it if not: on a Mac
   `brew install android-platform-tools`; on Windows use Google's
   platform-tools and Git Bash). Download the latest QuietTV release
   zip from https://github.com/vineetjk/quiettv/releases/latest and
   unzip it, or clone the repo.

2. Help me turn on developer mode on the TV (Settings > System > About,
   press OK on "Build" 7 times), then USB debugging and Network
   debugging in Developer options. If you don't know my TV's IP, tell
   me where to find it on the TV. Connect with `adb connect <IP>:5555`
   and tell me when to press "Allow" (with "Always allow" ticked).

3. Run `sh quiettv.sh scan <IP>` and explain the result: free memory,
   the apps that are safe to turn off, and what each one is.

4. quiettv.sh asks yes/no questions in the terminal, which you can't
   answer. So ask me in this chat first, then run the command with
   YES=1 only for what I agreed to:
   - `sh quiettv.sh clean <IP>`: junk apps to turn off
   - `sh quiettv.sh tune <IP>`: animations, screensaver, sleep timer
     (SLEEP_MINUTES=<n> sets it), background limits
   - `sh quiettv.sh install <IP> "<apps>"`: the home screen. Ask me
     which apps I watch, and list the TV's apps with
     `adb shell pm list packages -3` to find their package names.

5. Rules you must never break:
   - Never turn off or uninstall the TV's own home screen
     (com.google.android.apps.tv.launcherx or similar), its setup app
     (com.google.android.tungsten.setupwraith), Google Play services,
     Play Store, Settings, System UI, the keyboard, Bluetooth, HDMI
     inputs or the TV input framework. If unsure, leave it on.
   - Make changes only through quiettv.sh, so they're recorded on the
     TV and can be undone with `sh quiettv.sh undo <IP>`.
   - Never force-stop QuietTV (that switches its Home button off).

6. Check it works: ask me to press Home on the real remote (adb key
   presses don't count). Then restart the TV once with `adb reboot`,
   wait for it, run `sh quiettv.sh status <IP>`, and run
   `sh quiettv.sh reapply <IP>` if anything came back on.
   Show me free memory before and after.

7. If something fails or looks wrong, stop. Run
   `sh quiettv.sh report <IP>`, show me the output, and help me open an
   issue at https://github.com/vineetjk/quiettv/issues/new/choose
   using the "Setup help" form, with the report pasted in.
```

## Tips

- **Test the Home button with the real remote.** Key presses sent with `adb shell input` skip QuietTV's
  Home helper, so they don't show what happens with the remote.
- **Keep the TV awake while testing.** If it has a short sleep timer, wake it with
  `adb shell input keyevent KEYCODE_WAKEUP` before each check. Apps opened while the screen is off close
  straight away.
- **Stuck?** The report command gathers everything needed to help you, and leaves out your IP address:
  `sh quiettv.sh report <IP>`. Then [open an issue](https://github.com/vineetjk/quiettv/issues/new/choose).
