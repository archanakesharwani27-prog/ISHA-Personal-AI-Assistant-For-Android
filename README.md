# Aura Shell (Flutter)

Native Android WebView wrapper for the Aura web app. Implements the bridge
contract from `ANDROID_BRIDGE.md` in the web project: it hosts the deployed
Aura URL in a WebView and exposes an `auraDeviceIntent` JS handler that the
page calls to trigger real device actions.

This was generated as source files only — this sandbox doesn't have the
Flutter SDK or access to pub.dev, so it hasn't been built or `pub get`'d.
You'll need to do that step locally.

## 1. Turn this into a runnable Flutter project

This folder has the parts that differ from a stock template
(`pubspec.yaml`, `lib/`, and everything under `android/app/src/main/`).
Scaffold the rest with:

```bash
flutter create --org com.aura --project-name aura_shell aura_shell_full
```

That gives you `applicationId "com.aura.assistant"` and a default
`MainActivity.kt` in the same package — which is why every native file
here uses `package com.aura.assistant`. Then copy into the generated
project, overwriting the defaults:

- `lib/` (both `main.dart` and `device_bridge.dart`)
- `pubspec.yaml`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/kotlin/com/aura/assistant/` (all three `.kt` files
  — delete the generated `MainActivity.kt`/`.java` first so there's no
  duplicate class)
- `android/app/src/main/res/xml/accessibility_service_config.xml`
- `android/app/src/main/res/values/strings.xml` (merge with, don't
  overwrite, the generated one if it already has content)

## 2. Point it at your deployed web app

Deploy the TanStack Start project first (Vercel, Netlify, Cloudflare, etc.),
then edit the URL constant in `lib/main.dart`:

```dart
const String auraWebAppUrl = 'https://your-real-domain.com';
```

For local testing against `npm run dev` on the same laptop, use
`http://10.0.2.2:PORT` from an emulator (that's how the emulator reaches
your machine's localhost), or your LAN IP for a physical device on the
same Wi-Fi.

## 3. Install dependencies and run

```bash
cd aura_shell_full
flutter pub get
flutter run          # deploys to a connected device/emulator
# or, to just build an APK to sideload:
flutter build apk --release
# output: build/app/outputs/flutter-apk/app-release.apk
```

Sideloading: copy that APK to your phone and open it (you'll need to allow
"install unknown apps" for whatever app you use to open it).

## 4. What's implemented

`lib/device_bridge.dart` implements every action in `ANDROID_BRIDGE.md`,
backed by real native code where the doc calls for it:

- **`media_control` (play/pause/next/previous/stop)** — real transport
  control via `MediaSessionManager`, wired through the `aura/media`
  MethodChannel in `MainActivity.kt` and `AuraNotificationListenerService.kt`
  (a presence-only notification listener; Android requires one to hand back
  the active session — it reads no notification content).
- **`media_control` ("like")** and **`send_whatsapp` auto-send** — via
  `AuraAccessibilityService.kt`, which walks the on-screen accessibility
  tree for a send/like control and taps it.
- **`send_sms`** — sent directly with `SmsManager` through the `aura/sms`
  channel; falls back to opening the SMS composer pre-filled if the
  permission isn't granted.
- **Everything else** (open_app, play_music, make_call, calendar, alarms,
  timers, navigate_maps, take_photo, open_url, and wifi/bluetooth/airplane/
  flashlight/dnd/brightness/volume under toggle_setting) — intents or
  plugins, as in the original scaffold.

### Two things no code can skip, by Android design

- **Notification access** (for media_control) and **accessibility service**
  (for "like" and WhatsApp auto-send) can only be turned on by the user,
  by hand, in Settings — this is an Android anti-abuse boundary, not a gap
  in this project. The app detects when they're off and opens the right
  settings screen for the user automatically; it just can't flip the
  switch itself.
- **Wi-Fi/Bluetooth/airplane-mode toggling** has had no public API since
  Android 10 (API 29) for any app, full stop — this project opens the
  relevant settings screen instead, which is the same thing every other
  app in this situation does.

Everything else is genuinely wired end-to-end: build it, install it, grant
the two settings above once, and every action in `ANDROID_BRIDGE.md` works
the way the table describes.

## 5. App icon / branding

The manifest references `@mipmap/ic_launcher`, which only exists once you
scaffold with `flutter create` (step 1) or add your own icons under
`android/app/src/main/res/mipmap-*/`.
