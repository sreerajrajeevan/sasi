# Play Release Checklist — Sasi

## 1. Play Console setup
- [ ] Pay the one-time $25 developer registration and verify your identity.
- [ ] Create the app: name **Sasi — Pocket Companion**, default language,
      category (Lifestyle or Entertainment).

## 2. Store listing assets
- [ ] App icon: included (`mipmap-anydpi-v26` adaptive icon, cream background).
- [x] Feature graphic: **1024 × 500 px** banner — done, see `play-assets/feature_graphic.png`.
- [ ] Screenshots: at least 2 phone screenshots (Sasi floating over an app,
      the home dashboard). Record a short demo video if possible.
- [ ] Short + full description. Privacy policy URL (host
      `docs/PRIVACY_POLICY.md` on your site or a GitHub page).

## 3. Content rating
- [ ] Fill the questionnaire — expect **Everyone**.

## 4. Data safety form
- [ ] Declare **no data collected, no data shared** (no accounts, analytics,
      ads, or network calls).

## 5. Sensitive permission declarations
- [ ] **SYSTEM_ALERT_WINDOW** — core feature is the floating companion.
      In-app prominent disclosure is included in onboarding (step 2), and the
      listing should explain the overlay. Prepare a short demo video showing
      Sasi floating over other apps — reviewers often ask for one.
- [ ] **PACKAGE_USAGE_STATS** — used only on-device for the screen-time
      feature. Complete the declaration describing this.

## 6. Technical
- [ ] `targetSdk 36` (already set).
- [ ] Build a **signed release AAB**: Android Studio →
      `Build > Generate Signed App Bundle / APK` with your upload keystore.
- [ ] Enroll in **Play App Signing**; keep the upload keystore backed up
      somewhere safe (never commit it to git).
- [ ] Bump `versionCode` for every upload; keep `versionName` meaningful.

## 7. Testing tracks
- [ ] Upload the first AAB to the **internal testing** track and install it
      on a real device: verify overlay permission flow, usage-access flow,
      reminders fire, and the service survives reboot.
- [ ] Promote to closed → open testing, then production when stable.

## 8. Pre-launch report
- [ ] Review the automated pre-launch report for crashes on real devices
      (especially the overlay on different OEM skins).
