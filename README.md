# Pausely

Android app that limits time in apps like Instagram and X. Native Kotlin, no dependencies, **no permissions**, ~60 KB.

## Features
- Global default daily limit, with a custom limit per app
- 5-second pause screen before a limited app opens
- Full-screen block screen with rotating funny messages when a limit or schedule hits
- Schedules: global bedtime plus per-app windows, with day-of-week selection
- Puzzle gate (math, memory, Stroop) to raise a limit, remove an app or loosen a schedule; lowering is free
- Gradual reduction (optional): limits shrink 1 min per week down to 5 min
- Emergency unlock: once a day, +5 min, puzzle plus 30 s wait
- Quick panel from Android's floating accessibility button: usage, -5 min, toggles
- 30-day stats plus a log of the last 20 counted sessions (why each ended), settings export/import, in-app setup guide
- Searchable app picker with icons and a Suggested group
- Redesigned quick panel (progress per app, -5 min with undo, toggles)
- Loud in-app banner when protection (the accessibility service) is switched off
- Screen-reader labels, all text in string resources, 12/24-hour aware times

## How it works
An Accessibility service detects the foreground app (`TYPE_WINDOW_STATE_CHANGED` only; it cannot read screen content) and shows overlays. Usage is timed with a single timer per session, with no polling. Everything stays on the device; the app has no internet permission. See [PRIVACY.md](PRIVACY.md).

## Install (sideload)
1. Install `Pausely-<version>.apk`. If Play Protect blocks it, temporarily pause "Scan apps" in Play Protect settings.
2. App info -> menu -> **Allow restricted settings**.
3. Settings -> Accessibility -> Pausely -> On.
4. Battery -> Unrestricted (some phones kill the service otherwise).

The in-app **Setup guide** covers these steps and troubleshooting. A device test checklist is in [TESTING.md](TESTING.md).

## Build
Requires JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleRelease bundleRelease
```

Release signing reads `keystore.properties` (not committed; back up your keystore).

## Limitations
- Does not cover websites in a browser; add each app variant separately.
- The accessibility service can be switched off in system settings.
- Not yet reviewed for Google Play (accessibility declaration and consent flow needed).

## Developer
Built by **Sri Krishna Ayyanki**
- GitHub: https://github.com/sri347
- LinkedIn: https://www.linkedin.com/in/srikrishnaayyanki/
- Email: srikrishna.ayyanki@gmail.com

## License
GPL-3.0. See [LICENSE](LICENSE). Copyright (C) 2026 Sri Krishna Ayyanki.
