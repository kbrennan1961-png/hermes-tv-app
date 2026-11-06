# Hermes TV Chat App

Android TV app: press the mic button on your remote (or tap 🎤) → speak →
Hermes answers on screen AND speaks it back. Also supports "open Netflix" style commands.

## Build (on your laptop, needs Android Studio)

1. Install Android Studio (https://developer.android.com/studio). Accept default SDK (API 34).
2. Open this folder as a project.
3. Edit `app/src/main/java/com/hermes/tvchat/MainActivity.kt` CONFIG block:
   - For direct LLM (no computer needed): set apiBase to https://openrouter.ai/api/v1,
     apiKey to your OpenRouter key, model to a free model like
     "meta-llama/llama-3.3-70b-instruct:free"
   - For full Hermes (tools/memory): run hermes_bridge.py on your Linux box,
     set apiBase to http://<box-ip>:8765/v1, apiKey to anything, model to "hermes".
4. Build → Build APK(s). Find APK under app/build/outputs/apk/debug/.
5. Sideload: `adb install app-debug.apk` (box must have USB debugging on).

## Notes
- Free OpenRouter models have rate limits; for heavier use get a key with credits.
- Set a spending limit on whatever API key you use (creator's warning).
- The mic button on most Google TV remotes sends ACTION_SEARCH to the foreground
  app, which this app handles. If your remote's mic opens Google Assistant instead,
  use the on-screen 🎤 button or D-pad center.
- To revert: just uninstall the app; stock launcher is untouched.