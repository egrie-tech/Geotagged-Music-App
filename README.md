# Soundtrail — music for the places you love

An Android app that saves **Spotify**, **YouTube Music**, and **SoundCloud** song links as private, location-based pins, with a free **song lookup** on TheAudioDB. Find nearby pins, open a song in its music app, or view its spot in a map app. No account is needed to create pins manually.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| SoundCloud | Authorization Code **with PKCE** (OAuth 2.1) against `secure.soundcloud.com`. Search for tracks with the official API; tap a pin to open playback in SoundCloud or a browser. Tokens **and the Client Secret** are encrypted with Android Keystore. Requires an API app on SoundCloud's side — see below. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title on the Search tab to get exact names, album, genre/mood, and — when TheAudioDB knows one — a Spotify or YouTube link that passes the same allowlist, ready to prefill a pin. Metadata only; TheAudioDB never provides playback and nothing is stored. |
| YouTube Music | Paste or **Share → Soundtrail** a song link, then open it in the installed YouTube Music app or a browser. Sign in *inside that app*. On a compatible device, microG may support that app's Google sign-in. Soundtrail neither requests Google credentials nor reads a YouTube Music account. |
| Location | Android `LocationManager` (works without proprietary Play Services, including on microG devices). Requested only when you tap **Locate**; you can enter coordinates instead. Saved pins stay on the device. |

**Important:** microG is a replacement for parts of Google Play Services, **not** a YouTube Music catalog/playback API. There is no official YouTube Music library or audio-streaming integration in this app. It does not scrape YouTube, download audio, bypass subscriptions, install modified apps, or claim that package detection proves you are signed in. Spotify and SoundCloud playback also stay in their own apps rather than being streamed by Soundtrail.

**SoundCloud access:** SoundCloud treats *every* API client as confidential, so its token exchange requires both a Client ID **and** a Client Secret — unlike Spotify, there is no public-client mode. Registering an API app requires an eligible SoundCloud subscription or an approved API request, and registration may be limited; check [SoundCloud for Developers](https://developers.soundcloud.com/docs/api/register-app) for the current rules. The Secret is stored only in Android Keystore-encrypted storage on this device.

**TheAudioDB free key:** Soundtrail calls TheAudioDB's v1 API with their documented shared free key (`123`, formerly `2`). The shared key can be rerouted or rate-limited at any time — an inactive route returns HTTP 404 with `{"Message":"Not found"}`, and failures surface as a friendly lookup error. If it stops working, check [TheAudioDB's API page](https://www.theaudiodb.com/free_music_api) for the current key; premium keys use the same URL format and only require changing one constant (`AUDIO_DB_FREE_KEY`).

## Build & install

1. Install Android Studio with JDK 17 and Android SDK Platform 35. Open this directory as a Gradle project.
2. Run the `app` configuration on an Android 8.0+ device/emulator, or run:

   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. Optionally install Spotify, YouTube Music, and/or SoundCloud. An installed music app is **not** required for saving a pin; a browser can open supported links.
4. To use Spotify **search**: create an app at [Spotify for Developers](https://developer.spotify.com/dashboard), register the exact redirect URI below, and enter the app's public **Client ID** on Soundtrail's **Connect** tab. Do **not** enter a client secret.

   ```text
   app.soundtrail://spotify-auth
   ```

   Spotify requires this URI to match your dashboard setting exactly. Developer-mode apps have [access/allowed-user restrictions](https://developer.spotify.com/documentation/web-api/concepts/quota-modes); check the current requirements for your account. If the provider rejects a custom scheme for your particular app configuration, use a verified HTTPS Android App Link and update both the manifest and `SPOTIFY_REDIRECT_URI` accordingly.

5. To use SoundCloud **search**: register an API app per [SoundCloud's docs](https://developers.soundcloud.com/docs/api/register-app), register the exact redirect URI below in the app settings, and enter the **Client ID and Client Secret** on Soundtrail's **Connect** tab.

   ```text
   app.soundtrail://soundcloud-auth
   ```

   The URI must match your SoundCloud app settings exactly. Soundtrail uses the OAuth 2.1 Authorization Code flow with PKCE (`display=popup` for the mobile screen), exchanges the code at `secure.soundcloud.com/oauth/token`, and requests only `/me` and `/tracks` search. Access tokens expire after about an hour and refresh tokens are single-use; both are handled automatically while connected.

6. To use YouTube Music with microG, configure microG **and sign in within your own compatible YouTube Music app**. Soundtrail can open that app and receive shared links; no YouTube Music OAuth permission or microG SDK is required here. Standard `com.google.android.gms` installs cannot reliably be distinguished from microG by package name alone, so the status is informational only.

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter latitude/longitude manually; name the place and save.
- **Search:** connect Spotify or SoundCloud first, switch the card's provider chip, then tap a result to prefill a pin. Or paste a `music.youtube.com/watch?v=…` link.
- **Free lookup:** no account anywhere — type an artist and song title on the Search tab's **Free song lookup** card. Tap a result to prefill a pin with the exact artist and, when TheAudioDB knows one, a Spotify or YouTube link. Results marked "metadata only" still need a link pasted before saving.
- **Share:** in Spotify, YouTube Music, or SoundCloud, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Pins:** sort by distance after locating, open tracks in their music apps, view coordinates on a map, or remove local pins.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `soundcloud.com/<artist>/<track>` (including `www.`/`m.` hosts and `/s-<token>` secret trails), and SoundCloud's `on.soundcloud.com/<token>` or `soundcloud.app.goo.gl/<token>` share links. Tracking parameters are removed. Playlists, artist profiles, site pages, and unknown domains are rejected.

## Privacy and limitations

- Pins (song URL, title, note, place name, coordinates) are saved in private app storage, not uploaded. Location is foreground-only; there is no continuous tracking, map API key, analytics, or backend.
- Spotify access and refresh tokens, SoundCloud access/refresh tokens and Client Secret, and the short-lived PKCE verifier/state are stored with Android Keystore AES-GCM. Backups are disabled. Disconnect removes tokens **from this device**; SoundCloud disconnect also makes a best-effort `sign-out` call for the captured token. To revoke authorization at the provider, use your Spotify account settings or remove the app's access in your SoundCloud account.
- Provider APIs require network connectivity and provider-registered credentials; Spotify and SoundCloud can impose rate limits, permission requirements, or policy changes. The app only requests `user-read-private` (`/me`, `/search`) on Spotify and uses `/me` and `/tracks` search on SoundCloud.
- TheAudioDB lookups send the artist and title you type to `theaudiodb.com` and store nothing. Song links offered by lookup results come only from TheAudioDB's `strSpotifyID` and `strMusicVid` fields and must pass the same URL allowlist as manual links; a metadata-only result cannot be pinned until you add a supported link.
- YouTube Music does not expose account/library state to Soundtrail. Package detection reports an installed app or compatible services, **not** a confirmed signed-in account. Link handoff may fall back to a browser if the app does not accept the URL.
- Without a location fix, use manual coordinates to create pins; without a maps app, viewing a place falls back to OpenStreetMap in a browser.
- SoundCloud permalinks are slug-based, so links are canonicalized rather than re-derived from an ID. Because artist/track slugs share URL space with SoundCloud's own site sections, well-known non-artist paths (e.g. `/discover`, `/you/apps`) are rejected; an unexpected new site section could in principle be mistaken for a track, and tapping play would simply open that page.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Unit tests cover URL allowlisting/canonicalization (Spotify, YouTube Music, SoundCloud), TheAudioDB response parsing (canonical links, metadata-only results, null/malformed payloads), coordinate validation/distance, and the RFC 7636 PKCE challenge. The GitHub Actions Android workflow runs the same build and uploads a debug APK.

Documentation: [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [Spotify redirect rules](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri) · [SoundCloud API guide](https://developers.soundcloud.com/docs/api/guide) · [SoundCloud app registration](https://developers.soundcloud.com/docs/api/register-app) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [microG](https://microg.org/)
