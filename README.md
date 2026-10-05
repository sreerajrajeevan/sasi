# Sasi — Pocket Companion

A cute floating companion for Android. Sasi draws over other apps, wanders
around your screen, and gently reminds you about screen time: take a rest,
wind down at bedtime, and wake up to a greeting in the morning.

## Features

- **Floating overlay** — Sasi (a tiny blob with ears) walks, hops, idles, and
  blinks on top of any app. Tap it for a happy boop + heart pop; drag it
  anywhere; it resumes wandering after 3 seconds.
- **Wellness reminders** — rest breaks after N minutes of continuous use,
  bedtime nudges, morning greetings, 2h/4h/6h screen-time milestones, and idle
  chatter. Reminders appear as notifications *and* in Sasi's speech bubble.
- **Sleep mode** — inside your bedtime window Sasi gets sleepy, stops moving,
  and dims slightly.
- **Screen-time dashboard** — today's total, current session, and progress
  toward your daily goal (all computed on-device via UsageStatsManager).
- **Customization** — name, rest interval, bedtime/wake times, daily goal,
  size (S/M/L), walk speed (Slow/Normal/Zippy), and 4 body colors.
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
