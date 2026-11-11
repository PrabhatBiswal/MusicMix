# DJMix

A two-deck DJ console for Android phones, written in Kotlin with its own audio engine. A personal, just-for-fun
project, shared as-is.

- Two decks plus an 8-pad sampler mixed on one 48 kHz stream: 3-band EQ with kills, sweep filter, echo / roll / flanger,
  constant-power or cut crossfader, soft limiter, master recording to M4A
- BPM and beat-grid detection, sync, quantize, 8 hot cues, beat loops and jumps, keylock (time-stretch), slip mode
- Loudness matching (ITU-R BS.1770 to -14 LUFS) and a chorus finder that puts each song's hook on pad 1
- An automatic mashup sequencer with reel-style transitions (blend, filter, cut, hook swaps, dead-stop drops, rolls)
- Headphone cueing: the room on a Bluetooth speaker, the next track in wired earphones (or a splitter cable)
- Songs from YouTube, an Instagram reel shared to the app, or any audio file on the phone
- Optional cloud: sign in with Google to share one library between invited friends (Firebase)

The layers around the console (Hilt, ViewModels, StateFlow, Jetpack Compose) are explained in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Build

Android Studio, or the command line with JDK 17:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without a Firebase config the app builds and runs **local-only**: everything stays on the phone and the Cloud tab is
hidden.

## Your own cloud (optional)

1. Create a Firebase project and add an Android app with the package `com.prabhat.djmix` (or change `applicationId`).
2. Authentication → Sign-in method → enable **Google**.
3. Project settings → your Android app → add the **SHA-1** of the key you build with
   (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android` for debug builds).
4. Download `google-services.json` into `app/` and rebuild. The Cloud tab appears.
5. Sign in once in the app, copy your uid from Authentication → Users, then
   `cp storage.rules.example storage.rules`, put the uid in `admin()`, add friends' emails to `invited()`, and deploy:
   `firebase deploy --only storage`. Storage needs the Blaze plan; set a budget alert in Google Cloud.

## Release signing

Create a keystore outside the repo and point `keystore.properties` (git-ignored) at it:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`./gradlew :app:assembleRelease` then writes a signed `app/build/outputs/apk/release/app-release.apk`. Add that key's
SHA-1 to Firebase too, or Google sign-in fails in the release build. Back the keystore up: an APK signed with a
different key cannot update an installed one.

## Disclaimer

For personal and educational use. Downloading from YouTube or Instagram may break their terms of service, and music is
copyrighted: only use audio you have the right to use. The authors take no responsibility for how the app is used.

## License

GPL-3.0 (see [LICENSE](LICENSE)), as required by [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor),
which the app uses for YouTube. `app/src/main/java/sonic/Sonic.java` is Apache-2.0 (Bill Cox's Sonic library).
