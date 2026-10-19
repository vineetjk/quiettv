# Proposal: an on-TV "keeper" (not built, not shipped)

Some TVs (Acer R3_GTV, for one) turn disabled apps back on at every boot. This folder holds a design
that would let QuietTV put the changes back by itself: at boot, the app connects to the TV's own
debugging port (127.0.0.1:5555) with its own key, approved once on the TV, and re-runs only the
change list that `quiettv.sh` wrote (`off`, `setting`, `bg`, `a11y` lines, each strictly validated,
and never anything on `lists/protected.txt`).

**It is deliberately left out of the app.** An app that keeps a permanent, auto-approved shell
connection to the TV is a standing backdoor-shaped mechanism:

- "Always allow" gives the key full shell access for as long as it stays approved.
- The approval prompt can be spoofed by overlay attacks.
- It relies on network debugging staying on, which exposes port 5555 on the home network.

So QuietTV re-applies changes from your computer instead: `sh quiettv.sh reapply <TV_IP>`.

If you want to build the keeper anyway, review these three files first. You'd need to generate a
`Protected.java` from `lists/protected.txt` (Keeper calls `Protected.matches`), add the files to
`app/src/com/quiettv/home/`, add the `INTERNET` permission and an exported `KeeperActivity` to the
manifest, and call `Keeper.onGuardStarted(this)` from `HomeGuard.onServiceConnected()`. Protocol notes
and sources are in `docs/RESEARCH.md`.
