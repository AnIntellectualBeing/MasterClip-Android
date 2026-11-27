# 1.2.2 — Hinglish captions

- Hindi subtitle and speech text now uses the same local Hinglish spelling rules as the desktop app. English text and word timings are preserved.
- Speech recognition no longer repeats Hindi transcription with an unrelated example prompt.
- Replaced the tiny speech model with multilingual Whisper small and beam decoding to preserve spoken Hindi instead of producing English translations. The new model downloads once (about 182 MiB / 190 MB); transcription takes longer on the phone.
- Fixed overlapping highlights when words have very close or identical timestamps. Common English terms written in Hindi use their English spelling.
- Added regression coverage for mixed Hindi/English, punctuation, Hindi digits, and subtitle timing. Speech recognition can still mishear individual words; review captions before sharing.

## MasterClip for Android 1.2.1

- Captions now decode AAC audio that starts with a negative priming timestamp instead of treating it as an empty file.
- When a selected clip has no subtitle words, the app uses speech recognition even if subtitles exist elsewhere in the video.
- Words spoken across the start of a clip remain visible and are trimmed to the clip boundary.
- Website APK updates retain the existing signing certificate so current installations can update in place. This test-signed build is for website delivery; Google Play production signing is separate.

## MasterClip for Android 1.1.0

Turn any YouTube or Twitch video into vertical Shorts, Reels and TikToks, right on your phone. Install it over 1.0.0: your settings stay.

**What's new**
- **Captions on every clip.** Live clips, videos from your phone and YouTube videos without subtitles now get word-by-word captions. They are made on your phone. A small 31 MB caption model downloads once, the first time you need it. Hindi comes out in English letters.
- **Post from the phone.** Add your Zernio key under "Posting to Instagram and YouTube". Tap **Post** on a clip to post now or schedule it. Turn on **Auto-post every clip** to post new clips one after the other, even with the app closed. Instagram allows up to 10 posts a day, YouTube up to 50 a day per account.
- **Live streams.** Clip what already happened on a YouTube live, or record the next 5 to 30 minutes. Twitch recordings skip the ad pieces.
- **Search** YouTube and Twitch streams inside the app.
- **Faster.** Three clips from a 15-minute video in about a minute and a half.
- **Caption styles and templates.** Six caption looks, three positions, and layout cards.

**Which file do I download?**
- **MasterClip-1.1.0-arm64-v8a.apk**: almost every phone from 2017 onwards. Pick this one if you're not sure.
- **MasterClip-1.1.0-armeabi-v7a.apk**: very old or very cheap phones, if the first one says "App not installed". Captions are slower here.
- **MasterClip-1.1.0-x86_64.apk**: emulators and Chromebooks.

**How to install**
1. Download the APK on your phone and open it.
2. If your phone asks, allow "Install unknown apps" for your browser or Files app.
3. Open MasterClip, paste a link (or share it from the YouTube app), and tap **Make clips**.

No account and no sign-in. Clips stay on your phone until you share or post them.
