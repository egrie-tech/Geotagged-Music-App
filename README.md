# Soundtrail — music for the places you love

An Android app that saves **Spotify**, **YouTube Music**, and **SoundCloud** song links as private, location-based pins, with a free **song lookup** (TheAudioDB), local **playlists**, and opt-in **automations** that trigger a playlist from a place, a way of moving (driving/running/walking), or a time window.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| SoundCloud | Authorization Code **with PKCE** (OAuth 2.1) against `secure.soundcloud.com`. Search for tracks with the official API; tap a pin to open playback in SoundCloud or a browser. Tokens **and the Client Secret** are encrypted with Android Keystore. Requires an API app on SoundCloud's side — see below. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title to get exact names, album, genre/mood, and — when TheAudioDB knows one — a Spotify or YouTube link that passes the same allowlist. Metadata only; nothing is stored. |
| YouTube | Links are opened in YouTube's own apps or a browser. Soundtrail can auto-open YouTube **search results** when a song is added to a playlist (toggleable), but it **never downloads, scrapes, streams, or caches YouTube content** — that would violate YouTube's Terms of Service. Playback and offline saving belong inside YouTube's apps. |
| Location & motion | `LocationManager` on demand, plus an **opt-in** foreground watch service for automations. Motion (driving/running/walking) is inferred **on-device from GPS speed** — no Play Services, no Activity Recognition API, no advertising ID. |
| Time | The watch checks the current time every minute; automations can fire only inside a time window (wraps midnight). The Lists tab shows the live clock. |

## Playlists & automations

- **Playlists** (Lists tab) are named groups of songs stored on the device. A song needs a title; a supported link (Spotify / YouTube Music / SoundCloud) is optional — without one, tapping play searches YouTube for it.
- **Automations** point a playlist at conditions: *near a saved spot (20–2000 m radius)*, *while walking / running / driving*, and/or *between two times of day*. Set any combination; all set conditions must hold at once.
- **The watch**: an opt-in foreground service (persistent notification — required by Android and kept honest by it) checks location, motion, and time. When a rule matches it posts a notification with **Play playlist** and **Open Soundtrail** actions. Nothing plays without a tap, and a rule re-arms after a 15-minute cooldown.
- **Motion inference**: GPS speed bands — under 0.5 m/s stationary, to 2.6 walking, to 7.5 running (covers cycling), above that driving. It is an estimate; tunnels and poor GPS delay or miss triggers.
- **Battery**: continuous GPS watching uses noticeably more battery. The watch runs only while its toggle is on and its notification is visible.
- **Privacy**: coordinates, motion, playlists, and rules never leave the device. The only data ever sent anywhere remains the Spotify/SoundCloud API calls from your own connected accounts and TheAudioDB's artist/title query.

**Important:** microG is a replacement for parts of Google Play Services, **not** a YouTube Music catalog/playback API. There is no official YouTube Music library or audio-streaming integration here. The app does not scrape YouTube, download audio, bypass subscriptions, or install modified apps. Spotify and SoundCloud playback stay in their own apps rather than being streamed by Soundtrail.

**SoundCloud access:** SoundCloud treats *every* API client as confidential, so its token exchange requires both a Client ID **and** a Client Secret — unlike Spotify, there is no public-client mode. Registering an API app requires an eligible SoundCloud subscription or an approved API request; check [SoundCloud for Developers](https://developers.soundcloud.com/docs/api/register-app). The Secret is stored only in Android Keystore-encrypted storage on this device.

**TheAudioDB free key:** Soundtrail calls TheAudioDB's v1 API with their documented shared free key (`123`, formerly `2`). The shared key can be rerouted or rate-limited at any time — an inactive route returns HTTP 404 with `{"Message":"Not found"}`. If it stops working, check [TheAudioDB's API page](https://www.theaudiodb.com/free_music_api); premium keys use the same URL format and only require changing `AUDIO_DB_FREE_KEY`.

## Build & install

1. Install Android Studio with JDK 17 and Android SDK Platform 35. Open this directory as a Gradle project.
2. Run the `app` configuration on an Android 8.0+ device/emulator, or run:

   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. Optionally install Spotify, YouTube Music, and/or SoundCloud. An installed music app is **not** required for saving a pin; a browser can open supported links.
4. To use Spotify **search**: create an app at [Spotify for Developers](https://developer.spotify.com/dashboard), register the exact redirect URI below, and enter the app's public **Client ID** on the **Connect** tab. Do **not** enter a client secret.

   ```text
   app.soundtrail://spotify-auth
   ```

   Spotify requires this URI to match your dashboard setting exactly. Developer-mode apps have [access/allowed-user restrictions](https://developer.spotify.com/documentation/web-api/concepts/quota-modes). If the provider rejects a custom scheme for your configuration, use a verified HTTPS Android App Link and update both the manifest and `SPOTIFY_REDIRECT_URI`.

5. To use SoundCloud **search**: register an API app per [SoundCloud's docs](https://developers.soundcloud.com/docs/api/register-app), register the exact redirect URI below, and enter the **Client ID and Client Secret** on the **Connect** tab.

   ```text
   app.soundtrail://soundcloud-auth
   ```

   Soundtrail uses the OAuth 2.1 Authorization Code flow with PKCE (`display=popup` for the mobile screen), exchanges the code at `secure.soundcloud.com/oauth/token`, and requests only `/me` and `/tracks` search. Access tokens expire after about an hour; refresh tokens are single-use and handled automatically.

6. To use YouTube Music with microG, configure microG **and sign in within your own compatible YouTube Music app**. Soundtrail can open that app and receive shared links; no YouTube Music OAuth permission or microG SDK is required here.

## Permissions, and why

| Permission | Used for |
| --- | --- |
| Internet | Provider search APIs and TheAudioDB lookups |
| Coarse/Fine location | Pinning, sorting pins, and the opt-in automation watch |
| Foreground service (+ location type) | The watch's persistent, visible automation service — opt-in only |
| Post notifications | The watch status and automation trigger notifications (Android 13+) |

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter coordinates manually; name the place and save.
- **Search:** connect Spotify or SoundCloud and switch the provider chip, use the **free TheAudioDB lookup** (artist + title, no account), or paste a `music.youtube.com/watch?v=…` link.
- **Share:** in Spotify, YouTube Music, or SoundCloud, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Lists:** create a playlist, add songs (link optional), and turn on the watch. Create an automation — for example, *"Morning commute" → playlist "Drive" · while driving · 07:00–09:00* — and tap the notification when it fires.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `soundcloud.com/<artist>/<track>` (including `www.`/`m.` hosts and `/s-<token>` secret trails), and `on.soundcloud.com/<token>` or `soundcloud.app.goo.gl/<token>` share links. Tracking parameters are removed. Playlists, artist profiles, site pages, and unknown domains are rejected.

## Privacy and limitations

- Pins, playlists, and automations (song URLs, titles, notes, place names, coordinates, time windows) are saved in private app storage, not uploaded.
- Location is requested on demand for pins. The automation watch is a separate, explicit opt-in: a foreground service with a persistent notification that Android shows at all times while it runs. Turn the toggle off and every watch process stops.
- Spotify and SoundCloud tokens (and SoundCloud's Client Secret) and the short-lived PKCE verifier/state are stored with Android Keystore AES-GCM. Backups are disabled. Disconnect removes tokens from this device; SoundCloud disconnect also makes a best-effort `sign-out` call. Revoke access any time from your provider account settings.
- TheAudioDB lookups send the artist and title you type and store nothing. Lookup-offered links must pass the same URL allowlist as manual links.
- Soundtrail does **not** download, scrape, or re-host YouTube content. The YouTube integration is search-and-handoff only, by design and per YouTube's Terms of Service.
- Motion detection is a GPS-speed estimate, not a sensor fusion product: expect misses at poor accuracy, and driving/running thresholds are approximate. Automations use cooldown-based re-arming (15 minutes), not edge triggering, so a rule can re-fire after its cooldown if conditions still hold.
- Without a location fix, use manual coordinates to create pins; without a maps app, viewing a place falls back to OpenStreetMap in a browser.
- SoundCloud permalinks are slug-based and canonicalized rather than re-derived; well-known non-artist paths (e.g. `/discover`, `/you/apps`) are rejected, but an unexpected new site section could in principle parse as a track and would simply open that page.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Unit tests cover URL allowlisting/canonicalization (Spotify, YouTube Music, SoundCloud), TheAudioDB response parsing, motion classification bands, automation condition evaluation (place radius, motion kind, time windows including midnight wrap) with cooldown-based triggering, YouTube search URL building, coordinate validation/distance, and the RFC 7636 PKCE challenge. The GitHub Actions Android workflow runs the same build and uploads a debug APK.

Documentation: [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [SoundCloud API guide](https://developers.soundcloud.com/docs/api/guide) · [SoundCloud app registration](https://developers.soundcloud.com/docs/api/register-app) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [microG](https://microg.org/)
