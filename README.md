# MasterClip for Android

Turn any YouTube or Twitch video into vertical Shorts, Reels and TikToks, right on your phone.
This is the phone version of [MasterClip](https://github.com/AnIntellectualBeing/MasterClip) for Windows.

**Get it:** the app is sold at [masterprompt.anintellectualcompany.com/MasterClipPhone](https://masterprompt.anintellectualcompany.com/MasterClipPhone/) (₹149). The code is open source and free to read and build; the ready-to-install APK is sold on the website and is not published here.

**Open source (MIT).** You can read, change and build this code yourself with Android Studio (`./gradlew assembleRelease`). No APK files are published in this repo or its Releases.


## Caption development handoff (1.2.2)

See [the handoff](docs/hinglish-handoff.txt) for the current implementation, remaining recognition errors, phone test results, and reproduction steps. Hindi retention and video export are verified on synthetic examples; natural human Hinglish accuracy still needs work.

## What's new in 1.2.1

- Fixed missing speech captions on AAC audio with negative starting timestamps.
- Clips without subtitle words now use speech recognition, even when another part of the video has subtitles.
- Words crossing the beginning of a clip are kept and trimmed to the clip boundary.

## What's new in 1.2

- **Campaigns:** open it from the start screen. See clipping campaigns that pay per views and sites that run them. **Clip for this** fills in the link (or searches the streamer), sets the clip length, turns captions on and shows a Campaign banner.
- **My submissions:** add the link of a clip you posted, type its views, and see an estimate (views / 1000 x the campaign's rate) and a total. It is only an estimate.
- The list comes from anintellectualcompany.com/campaigns.json. Offline, the app shows the last saved list, or the one built into the app.

## What's new in 1.1

- **Much faster.** Reads the video once and downloads only the chosen parts, two at a time while the last clip is made. Three clips from a 15-minute video take about a minute and a half.
- **Live streams.** Clip what already happened on a YouTube live, or record the next 5 to 30 minutes. Twitch lives are recorded with ad pieces skipped.
- **Search.** Find YouTube and Twitch streams inside the app and see which are live now.
- **Caption styles and templates.** Six caption looks, three positions, and template cards for the layout (face cam + gameplay, follow the face, full video + blur).
- **Captions on every clip.** Speech to text runs on the phone (whisper.cpp), so live clips, videos from your phone and YouTube videos without subtitles get word-by-word captions too. The 182 MiB (190 MB) caption model downloads once, the first time you need it. YouTube's own subtitles are still used when they exist. Hindi is written in English letters.
- **Post from the phone.** Add your Zernio key under **Posting to Instagram and YouTube**, tap **Post** on a clip to post now or schedule it, or turn on **Auto-post every clip** with a gap between posts. Instagram allows up to 10 posts a day and YouTube up to 50 a day per account.

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
| Captions | YouTube's word timings when there are any, else speech to text on the phone. 1–3 words at a time, the spoken word in yellow | `engine/Captions.kt`, `engine/Speech.kt` (whisper.cpp in `app/src/main/cpp`), `engine/CaptionOverlay.kt` |
| Post | Upload the clip and create the post, now or scheduled | Zernio API: `engine/Zernio.kt`, `engine/Posting.kt` |

The look follows one rule: **a face-cam layout always keeps the face in view, and with no face the whole video goes in the middle.**

yt-dlp updates itself once a day, because YouTube changes often.

## Build

Needs Android Studio's JDK (17+), the Android SDK (compileSdk 37), and the NDK 27 with CMake 3.22 (for whisper.cpp).

```
gradlew :app:testDebugUnitTest      # unit tests (moments, captions, layout, text)
gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-<abi>-debug.apk
gradlew :app:assembleRelease -PsmokeTest   # R8 release, debug-signed, for testing
tools\release.ps1 -NoUpload         # signed release APKs with the master key, kept local
```

## Privacy

There is no account, no analytics and no server. Videos come straight from YouTube or Twitch to the phone, and clips stay on the phone until you share them or post them yourself. Captions are made on the phone. Your Zernio key, if you add one, is stored encrypted on the phone and only sent to Zernio.

© An Intellectual Company
