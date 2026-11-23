# MasterClip for Android

Turn any YouTube or Twitch video into vertical Shorts, Reels and TikToks, right on your phone.
This is the phone version of [MasterClip](https://github.com/AnIntellectualBeing/MasterClip) for Windows.

**Download:** get the APK from [Releases](../../releases/latest). Most phones need `arm64-v8a`.

## How to use it

1. Paste a YouTube or Twitch link. You can also tap **Share › MasterClip** in the YouTube app, or pick a video from your phone.
2. Choose how many clips you want, how long each one is, and the look.
3. Tap **Make clips**. You can lock the phone while it works. The clips appear in your Gallery under **Movies › MasterClip**.

## How it works

| Step | What happens | Built with |
|---|---|---|
| Read the video | Title, length, the "most replayed" graph and the caption list | yt-dlp (via youtubedl-android, with Python, ffmpeg and QuickJS) |
| Pick moments | Peaks of the "most replayed" graph. Without it, sound loudness and sudden jumps | `engine/Moments.kt` |
| Download | Only the chosen parts (`--download-sections`), up to 1080p, H.264 first | yt-dlp + ffmpeg |
| Find faces | 8 frames per clip | ML Kit face detection (on the phone) |
| Choose a look | Face cam on top / follow the face / whole video on a blurred background | `engine/Layout.kt` |
| Make the clip | One GPU pass to 1080×1920, then the hardware H.264 encoder | Media3 Transformer + `engine/LayoutEffect.kt` |
| Captions | YouTube's word timings, 1–3 words at a time, the spoken word in yellow | `engine/Captions.kt`, `engine/CaptionOverlay.kt` |

The look follows one rule: **a face-cam layout always keeps the face in view, and with no face the whole video goes in the middle.**

yt-dlp updates itself once a day, because YouTube changes often.

## Build

Needs Android Studio's JDK (17+) and the Android SDK (compileSdk 37).

```
gradlew :app:testDebugUnitTest      # unit tests (moments, captions, layout, text)
gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-<abi>-debug.apk
gradlew :app:assembleRelease -PsmokeTest   # R8 release, debug-signed, for testing
tools\release.ps1                   # signed release APKs with the master key + GitHub upload
```

## Privacy

There is no account, no analytics and no server. Videos come straight from YouTube or Twitch to the phone, and clips stay on the phone until you share them.

© An Intellectual Company
