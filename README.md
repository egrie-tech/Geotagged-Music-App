# Soundtrail — music for the places you love

An Android app that saves **Spotify** and **YouTube Music** song links as private, location-based pins. Find nearby pins, open a song in its music app, or view its spot in a map app. No account is needed to create pins manually.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| YouTube Music | Paste or **Share → Soundtrail** a song link, then open it in the installed YouTube Music app or a browser. Sign in *inside that app*. On a compatible device, microG may support that app's Google sign-in. Soundtrail neither requests Google credentials nor reads a YouTube Music account. |
| Location | Android `LocationManager` (works without proprietary Play Services, including on microG devices). Requested only when you tap **Locate**; you can enter coordinates instead. Saved pins stay on the device. |

**Important:** microG is a replacement for parts of Google Play Services, **not** a YouTube Music catalog/playback API. There is no official YouTube Music library or audio-streaming integration in this app. It does not scrape YouTube, download audio, bypass subscriptions, install modified apps, or claim that package detection proves you are signed in. Spotify playback also stays in Spotify rather than being streamed by Soundtrail.

## Build & install

1. Install Android Studio with JDK 17 and Android SDK Platform 35. Open this directory as a Gradle project.
2. Run the `app` configuration on an Android 8.0+ device/emulator, or run:

   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. Optionally install Spotify and/or YouTube Music. An installed music app is **not** required for saving a pin; a browser can open supported links.
4. To use Spotify **search**: create an app at [Spotify for Developers](https://developer.spotify.com/dashboard), register the exact redirect URI below, and enter the app's public **Client ID** on Soundtrail's **Connect** tab. Do **not** enter a client secret.

   ```text
   app.soundtrail://spotify-auth
   ```

   Spotify requires this URI to match your dashboard setting exactly. Developer-mode apps have [access/allowed-user restrictions](https://developer.spotify.com/documentation/web-api/concepts/quota-modes); check the current requirements for your account. If the provider rejects a custom scheme for your particular app configuration, use a verified HTTPS Android App Link and update both the manifest and `SPOTIFY_REDIRECT_URI` accordingly.

5. To use YouTube Music with microG, configure microG **and sign in within your own compatible YouTube Music app**. Soundtrail can open that app and receive shared links; no YouTube Music OAuth permission or microG SDK is required here. Standard `com.google.android.gms` installs cannot reliably be distinguished from microG by package name alone, so the status is informational only.

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter latitude/longitude manually; name the place and save.
- **Search:** connect Spotify first to search tracks, then tap a result to prefill a pin. Or paste a `music.youtube.com/watch?v=…` link.
- **Share:** in Spotify or YouTube Music, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Pins:** sort by distance after locating, open tracks in their music apps, view coordinates on a map, or remove local pins.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`. Tracking parameters are removed. Playlists and unknown domains are rejected.

## Privacy and limitations

- Pins (song URL, title, note, place name, coordinates) are saved in private app storage, not uploaded. Location is foreground-only; there is no continuous tracking, map API key, analytics, or backend.
- Spotify access and refresh tokens, and the short-lived PKCE verifier/state, are stored with Android Keystore AES-GCM. Backups are disabled. Disconnect removes tokens **from this device**; to revoke authorization at Spotify, use your Spotify account settings.
- Spotify API access requires network connectivity and a developer-registered Client ID; Spotify can impose rate limits, permission requirements, or policy changes. The app only requests `user-read-private` and uses `/me` and `/search`.
- YouTube Music does not expose account/library state to Soundtrail. Package detection reports an installed app or compatible services, **not** a confirmed signed-in account. Link handoff may fall back to a browser if the app does not accept the URL.
- Without a location fix, use manual coordinates to create pins; without a maps app, viewing a place falls back to OpenStreetMap in a browser.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Unit tests cover URL allowlisting/canonicalization, coordinate validation/distance, and the RFC 7636 PKCE challenge. The GitHub Actions Android workflow runs the same build and uploads a debug APK.

Documentation: [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [Spotify redirect rules](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri) · [microG](https://microg.org/)
