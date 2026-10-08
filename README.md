# Sasi — Pocket Companion

A cute floating companion for Android. Sasi draws over other apps, wanders
around your screen, and gently reminds you about screen time: take a rest,
wind down at bedtime, and wake up to a greeting in the morning.

## Features

- **Floating overlay** — Sasi is a tiny vector cat (round head, triangle ears, wagging
  tail, little paws) with whiskered faces for every mood.
- **Edge peek** — instead of floating around all the time, Sasi lives as a
  small sliver at the screen edge (left, right, or bottom — one ear, one eye,
  whiskers peeking out, like peeking into a room). It only comes fully on
  screen when you tap it or when a real reminder fires; idle chatter stays
  silent while it peeks. Tap the sliver
  and it slides out for a visit, then slides back after ~30 seconds.
  Drag it near an edge to dock it on that side. Warnings slide it out to
  deliver their message, then it returns to the edge. Toggle "Peek from screen
  edge" in Settings to go back to free-floating.
- **Moods** — Sasi reacts to your day: happy after you interact, worried after
  an hour of continuous screen time, tired after 90 minutes, sleepy in your
  bedtime window (movement pauses then). Each mood gets its own cat face.
- **Long-press hide menu** — hold Sasi for a second to hide it for 15 minutes
  or an hour, until you lock your screen, until the next reminder, or turn it
  off entirely. Sasi only pops back for real warnings (rest, bedtime, wake);
  gentle nudges and chatter are bubble-only and never interrupt.
- **Wellness reminders** — rest breaks after N minutes of continuous use
  (with a soft bubble-only nudge at 45 min), bedtime nudges, morning
  greetings, 2h/4h/6h screen-time milestones, and idle chatter. Real warnings
  appear as notifications *with sound* and in Sasi's speech bubble.
- **Movement & interaction settings** — roam style (Free / Edge / Calm /
  Locked), wander frequency (Low / Normal / High), tap reactions, speech
  bubbles, and haptic feedback toggles.
- **Sleep mode** — inside your bedtime window Sasi gets sleepy, stops moving,
  and dims slightly.
- **Screen-time dashboard** — today's total, current session, and progress
  toward your daily goal (all computed on-device via UsageStatsManager).
- **Focus mode (Phase 2)** — start 15/25/45/60-minute or custom focus sessions
  (optional repeating Pomodoro: 25 min focus + 5 min break) from the Home
  screen. While focusing, Sasi calms down, shows a 🎯 face, and gives a quiet
  timer bubble every ~5 minutes; finishing earns a celebration. Cancelling is
  one tap, from Home or the notification.
- **Break mode (Phase 2)** — 20-second, 2-minute, 5-minute, or custom breaks
  with a gentle start/end (bubble + silent notification, never an alarm).
- **Simple stats (Phase 2)** — last 14 days of screen time, sessions, and
  focus minutes stored on-device; Home shows a 7-day bar summary with weekly
  totals and your best focus day.
- **Progression (Phase 3)** — Sasi grows with your healthy habits: XP and
  levels (100 → 300 → 600…), coins, energy, and bond. Focus sessions, breaks,
  respected rest reminders, staying under your daily goal, winding down before
  bed, and saying hi all earn XP — raw screen time never does. Energy and bond
  have floors and recover quickly; nothing is ever punished, and Sasi never
  dies. Includes 3 deterministic daily missions and 10 one-time achievements,
  all shown on the Home screen.
- **Privacy-first** — everything stays on the device. No accounts, no ads,
  no analytics, no network calls. See [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md).

## Project

- Kotlin, Jetpack Compose UI, classic Views overlay via `WindowManager`
  (`TYPE_APPLICATION_OVERLAY`).
- `minSdk 26`, `targetSdk 36`, single `:app` module, `com.sree.sasi`.
- Settings persisted with DataStore; foreground service (`specialUse`) keeps
  the overlay alive; `BootReceiver` restarts it after reboot.

## Build

**Android Studio:** open this folder, let Gradle sync, then Run ▶ (or
`Build > Build APK`). A debug APK is enough to try it on your phone.

**CI:** every push to `main` builds `:app:assembleDebug` and uploads the APK
as the `app-debug` artifact (`.github/workflows/android.yml`).

**Release AAB for Play:**
1. In Android Studio: `Build > Generate Signed App Bundle / APK`.
2. Create (or reuse) an upload keystore and build the **Android App Bundle**.
3. Enroll in **Play App Signing** (recommended) — Play manages the final
   signing key, you keep the upload key.
4. Upload the `.aab` to the Play Console (internal testing track first).

> Do not commit your keystore or its passwords to git.

## Permissions — why each is needed

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Core feature: draw Sasi over other apps. Requested in onboarding with a prominent disclosure. |
| `PACKAGE_USAGE_STATS` | Screen-time totals and session tracking, computed on-device only. Granted via system settings. |
| `POST_NOTIFICATIONS` | Reminder notifications (rest, bedtime, milestones). |
| `FOREGROUND_SERVICE` + `specialUse` | Keep the companion overlay running reliably. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional step so aggressive OEM battery savers don't kill Sasi. |
| `RECEIVE_BOOT_COMPLETED` | Restart the companion after a reboot (only if it was on). |

## Repo layout

```
app/src/main/java/com/sree/sasi/
  MainActivity.kt            Compose entry: onboarding / home / settings
  SasiApp.kt                Application: owns the Prefs singleton
  BootReceiver.kt            Restarts the service after boot
  data/Prefs.kt              DataStore settings + reminder dedupe keys
  util/Notif.kt              Notification channel + service/reminder notifications
  screentime/ScreenTimeTracker.kt  UsageStats totals + continuous session
  reminders/ReminderEngine.kt      Pure reminder decision logic
  overlay/CompanionService.kt      Foreground service + 5s tick loop
  overlay/CompanionView.kt         The floating character (Views, WindowManager)
  ui/                        Compose screens + Material3 theme
app/src/main/res/            Vectors (Sasi body/faces/heart), launcher icon, strings
docs/                        Privacy policy + Play release checklist
```
