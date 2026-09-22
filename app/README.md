# RAG Knowledge Base App

Flutter app for the RAG knowledge base MVP.

## Run

```bash
flutter pub get
dart run build_runner build
flutter run --dart-define=API_BASE_URL=http://localhost:8080
```

The app shows an explicit login page and stores only the returned token in
platform secure storage. The default demo account can be entered manually:

```text
用户名：demo
密码：demo123456
```

The username field can be prefilled at build time if needed:

```bash
flutter run \
  --dart-define=API_BASE_URL=http://localhost:8080 \
  --dart-define=APP_USERNAME=demo
```

## Android Physical Device

When the backend runs on the development machine:

```bash
adb reverse tcp:8080 tcp:8080
flutter run --dart-define=API_BASE_URL=http://localhost:8080
```

If `adb reverse` is unavailable, use the computer's LAN IP:

```bash
flutter run --dart-define=API_BASE_URL=http://192.168.x.x:8080
```

Debug Android enables cleartext HTTP. Release deployments should use HTTPS.

## Screens

- `问答`: chat bubbles, loading answer, source chips with snippet bottom sheet, grounded/refusal state, latency/token/failure metadata, and helpful/not-helpful feedback.
- `知识库`: upload PDF/TXT/MD/DOCX, poll processing, delete, disable, and reindex visible documents; backend permissions remain the security boundary.

## Toolchain Pins

Android matches `ai-weekly-report`:

- Android Gradle Plugin: `8.7.3`
- Gradle wrapper: `8.11.1`
- Kotlin plugin: `2.1.0`
