# GhostDraft — Android floating mic bubble

A draggable mic button that floats over every app. Tap = listen, tap again =
stop, and your speech is typed straight into whatever text field is focused —
via an AccessibilityService. The Android counterpart to the macOS GhostDraft.

Speech is transcribed by Android's built-in `SpeechRecognizer` (on-device when
the offline pack is present, automatic online fallback otherwise). No API key,
no model download. The backend sits behind a `Recognizer` interface, so a
whisper.cpp/NDK or cloud-Whisper backend can be dropped in later without
touching the bubble or the text injection.

---

## Get the APK without installing anything (recommended)

You don't need Android Studio or the SDK on your Mac. Let GitHub build it:

1. Create a new **empty** GitHub repo (private is fine).
2. From this project folder, push it up:
   ```bash
   git init && git add . && git commit -m "GhostDraft bubble"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the repo's **Actions** tab. The `Build APK` workflow runs automatically.
4. When it finishes (~3–4 min), open the run and download the
   **GhostDraft-debug-apk** artifact. Unzip it → `app-debug.apk`.
5. Copy the APK to your phone and tap it to install (allow "install from
   unknown sources" when prompted).

That's the whole loop. Re-push to rebuild; trigger manually anytime from the
Actions tab via **Run workflow**.

---

## Build locally instead (if you have the Android SDK)

```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```
First run downloads Gradle 8.9 + the Android Gradle Plugin + AndroidX. Requires
a JDK 17 and the Android SDK (Android Studio installs both). Or open the folder
in Android Studio and press Run.

---

## First-run setup on the phone

Open GhostDraft and grant, in order:

1. **Microphone** — so it can hear you.
2. **Draw over apps** — so the bubble floats on top of everything.
3. **Accessibility** — so it can type into other apps' fields. Android shows a
   scary-sounding warning; that's expected for a sideloaded app. The service
   only writes dictated text into the field you've focused; it does nothing on
   its own.

Then tap **Start the floating mic**. A blue mic appears. Drag it anywhere. Tap
into a text field in any app, tap the bubble (turns red = listening), speak,
tap again — the text lands in the field.

---

## How it fits together

| Piece | Role |
|-------|------|
| `MainActivity` | Permission onboarding + start/stop toggle |
| `BubbleService` | Foreground overlay mic; drag-vs-tap; non-focus-stealing window |
| `recognizer/Recognizer` | Swappable transcription interface |
| `recognizer/AndroidSpeechRecognizer` | Default backend (on-device, online fallback) |
| `GhostDraftAccessibilityService` | Cursor-aware `SET_TEXT` into the focused field, clipboard-paste fallback |

The overlay window uses `FLAG_NOT_FOCUSABLE` — that's the key detail. Tapping
the bubble doesn't pull focus away from the text field underneath, so the
accessibility service can still find and write into it.

## Notes & limits

- minSdk 26 (Android 8.0), targetSdk 34. Tested-clean target is modern Android.
- A few apps reject programmatic text insertion (some password fields, certain
  remote-desktop/secure views). There the text falls back to the clipboard and
  a toast tells you to paste manually.
- `SpeechRecognizer` finalizes one utterance per tap. For long dictation just
  tap again to continue. Continuous/streaming dictation is a future backend.
- The release build is signed with the debug key for easy sideloading; swap in
  a real keystore before any distribution.
