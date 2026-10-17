# Jarvis Android Assist App

Hold home button → Jarvis opens → talk → Jarvis responds.  
**One login. Forever. Encrypted session that survives reinstalls.**

---

## Testing Online (No Real Device Needed)

### appetize.io — Recommended

1. Go to **https://appetize.io**
2. Click **"Get started for free"** → upload your APK (free tier: 100 min/month)
3. Select **Android 12, Pixel 5** as the device
4. Click **Run** — full Android emulator in your browser
5. Test the app: tap **"Test Voice Now"** (home-hold only works on real device)
6. appetize.io supports mic input from your computer microphone

### BrowserStack App Live (alternative)
- https://app-automate.browserstack.com/dashboard
- More device options, has free trial

---

## Build Instructions (Android Studio)

### Prerequisites
- Android Studio Hedgehog or newer (free)
- JDK 11+

### Steps
```
1. Open Android Studio
2. File → Open → select the JarvisAndroid folder
3. Let Gradle sync (first time takes ~3 min downloading deps)
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. APK lands at:  app/build/outputs/apk/debug/app-debug.apk
6. Upload that APK to appetize.io
```

---

## How Persistent Login Works

`SessionStore.java` uses **AndroidX EncryptedSharedPreferences** backed by **AES256-GCM** in the Android Keystore.

- **First launch:** You enter the Jarvis URL + your name once → taps Save
- **Every launch after:** Setup screen is skipped entirely. Goes straight to dashboard.
- **Session ID:** Generated once as `android_<UUID>`. Reused on every `/api/chat` call.
  Jarvis recognizes it and maintains conversation context.
- **Reset:** Tap "Reset / Change URL" in the dashboard → confirm → wipes encrypted store.

---

## Project Structure

```
JarvisAndroid/
├── app/src/main/
│   ├── AndroidManifest.xml          ← registers assist service + permissions
│   ├── java/com/jarvis/assist/
│   │   ├── storage/
│   │   │   └── SessionStore.java    ← encrypted persistent session (NEVER expires)
│   │   ├── network/
│   │   │   └── JarvisClient.java    ← OkHttp → POST /api/chat with retry
│   │   ├── ui/
│   │   │   ├── MainActivity.java    ← one-time setup / dashboard
│   │   │   └── VoiceOverlayActivity.java  ← full HUD: STT → Jarvis → TTS
│   │   ├── JarvisAssistService.java ← VoiceInteractionService (home-hold trigger)
│   │   ├── JarvisSessionService.java← foreground service (keeps app registered)
│   │   └── BootReceiver.java        ← restart after device reboot
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml
│       │   └── activity_voice_overlay.xml
│       ├── values/{strings,themes}.xml
│       ├── drawable/{ic_jarvis,circle_pulse,input_bg}.xml
│       └── xml/interaction_service.xml
└── app/build.gradle                 ← OkHttp + security-crypto + AppCompat
```

---

## Making Jarvis the Default Assist App (Real Device)

After installing APK:
1. Settings → Apps → Default Apps → Digital Assistant
2. Select **Jarvis**
3. Now home-hold fires Jarvis instead of Gemini

---

## API Call Format

```json
POST https://mcujarvis.onrender.com/api/chat
{
  "message":      "What's the weather?",
  "sessionId":    "android_abc123...",
  "userName":     "Sir",
  "userTitle":    "Sir",
  "userTimezone": "America/New_York"
}
```
