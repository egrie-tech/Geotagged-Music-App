# Soundtrail — music for the places you love

An Android app that saves **Spotify**, **YouTube Music**, **SoundCloud**, and **your own Jellyfin server's** songs as private, location-based pins, with an **in-app map** of those pins, a free **song lookup** (TheAudioDB), local **playlists** with **imported offline audio**, opt-in **automations** that trigger a playlist from a place, a way of moving (driving/running/walking), or a time window, **JSON backup/export** of everything local, and **background Jellyfin playback** with system media controls.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| SoundCloud | Authorization Code **with PKCE** (OAuth 2.1) against `secure.soundcloud.com`. Search for tracks with the official API; tap a pin to open playback in SoundCloud or a browser. Tokens **and the Client Secret** are encrypted with Android Keystore. Requires an API app on SoundCloud's side — see below. |
| Jellyfin (your own server) | Username + password sign-in against **your** Jellyfin server (`/Users/AuthenticateByName`), then read-only library search and in-app streaming of the songs you already host — in the app, or in the background under a MediaStyle playback notification with lockscreen/Bluetooth controls. The password is used once and **never stored**; the returned access token is encrypted with Android Keystore. Works over HTTPS **or** plain HTTP on your home network. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title to get exact names, album, genre/mood, and — when TheAudioDB knows one — a Spotify or YouTube link that passes the same allowlist. Metadata only; nothing is stored. |
| YouTube Music app | Search and Library links are handed to the installed YouTube Music app, including Morphe's patched package, or to music.youtube.com. Share a song link back to Soundtrail to pin it. Soundtrail never reads the music app's private storage or session. |
| YouTube playlist search (optional) | Explicit Google Sign-In requests the `youtube.readonly` scope and calls the official YouTube Data API v3 for videos in your own playlists. The OAuth access token is requested from Google services when needed, held in memory for API calls, and never persisted by Soundtrail. A compatible microG/GmsCore installation may provide the required sign-in APIs, but support depends on its version, device, account, package certificate, and OAuth setup. This does **not** expose the full YouTube Music library (saved songs, albums, mixes, etc.); see setup and scope limits below. |
| YouTube links | The **Get** button shares a song link or YouTube Music search URL through Android's share sheet. Soundtrail bundles no downloader, scrapes nothing, and does not stream YouTube content. |
| Your audio files | Import audio you already have (including MP3s you made with external tools such as yt-dlp) into a playlist. Files are copied into private app storage and played offline in your chosen music player. |
| Map tiles | The Pins tab's **Map** view draws an OpenStreetMap-based map on-device: raster tiles are fetched from CARTO's public tile server (`basemaps.cartocdn.com`) for the visible area only, with a descriptive User-Agent. Tile requests reveal which part of the map you are looking at (that is the nature of tiled maps) — pin coordinates themselves are rendered locally and never sent anywhere. Attribution stays on screen. |
| Backup files | **Export** writes pins, playlists, and automations to one JSON document through the system file picker; **Import** reads one back, additively. The file goes only where you put it — Soundtrail has no server to upload to. Logins and imported audio files are deliberately excluded. |
| Location & motion | `LocationManager` on demand, plus an **opt-in** foreground watch service for automations. Motion (driving/running/walking) is inferred **on-device from GPS speed** — no Play Services, no Activity Recognition API, no advertising ID. |
| Time | The watch checks the current time every minute; automations can fire only inside a time window (wraps midnight). The Lists tab shows the live clock. |

## Playlists & automations

- **Playlists** (Lists tab) are named groups of songs stored on the device. A song needs a title; a supported link (Spotify / YouTube Music / SoundCloud / Jellyfin) is optional — without one, tapping play opens a YouTube Music search in the installed app or browser.
- **Jellyfin songs** are stored as *item references* (`jellyfin:item:<id>`), never as server URLs: no address, username, or token ends up in a pin, a playlist, or a shared backup, and the same song keeps working after your server moves to another host or port. Playback is resolved against the current connection whenever you tap play, and streamed **inside Soundtrail** by the now-playing bar above the tabs — in the app, or in the background via the playback notification (see *Privacy and limitations*) — direct play first, with an automatic transcode to MP3 if your device's decoder cannot open the stored format.
- **Backups:** the Connect tab can **export** every pin, playlist (with song links), and automation to a JSON file you choose, and **import** one back. Import is additive: ids already on the device are kept as they are, so a restore never overwrites a local edit, and re-importing the same file does nothing. Account logins (Keystore-encrypted) and imported audio files stay on the device they were made on — a restored playlist shows which songs need audio re-attached.
- **Get a song (external handoff):** the ⬇ button shares the song's link (or its YouTube Music search URL) through Android's share sheet. It does not target a downloader or download anything itself.
- **Import audio:** the ♪ button attaches an audio file from your device to a playlist song. The file is copied into private storage and shows as *On device*; tapping play opens it in your music player, fully offline. Removing the song or playlist deletes the copy.
- **Automations** point a playlist at conditions: *near a saved spot (20–2000 m radius)*, *while walking / running / driving*, and/or *between two times of day*. Set any combination; all set conditions must hold at once.
- **The watch**: an opt-in foreground service (persistent notification — required by Android and kept honest by it) checks location, motion, and time. When a rule matches it posts a notification with **Play playlist** and **Open Soundtrail** actions. Nothing plays without a tap, and a rule re-arms after a 15-minute cooldown.
- **Motion inference**: GPS speed bands — under 0.5 m/s stationary, to 2.6 walking, to 7.5 running (covers cycling), above that driving. It is an estimate; tunnels and poor GPS delay or miss triggers.
- **Battery**: continuous GPS watching uses noticeably more battery. The watch runs only while its toggle is on and its notification is visible.
- **Privacy**: coordinates, motion, local playlists, rules, and imported audio never leave the device. The one map-related exception is tile fetching: opening the map asks CARTO's public tile server for the map area on screen (see the map-tiles row above) — pin coordinates themselves are rendered locally. When you explicitly search or open a song, Soundtrail calls only the selected provider. For YouTube playlist search, it downloads metadata from the first pages of your own playlists and matches your query **on-device**; the query itself is not sent to YouTube. Other API requests go only to Spotify/SoundCloud (for connected accounts), TheAudioDB, your own Jellyfin server, or the map tile server.

## Using yt-dlp with Soundtrail (your tools, your responsibility)

Soundtrail deliberately contains **no downloader** — embedding one would require circumventing YouTube's technical protections, which violates YouTube's Terms of Service and would put the app (and you) on the wrong side of it. What Soundtrail *does* is make your own yt-dlp workflow one tap away:

1. **Hand off:** on the Lists tab, open a playlist and tap **⬇ Get** on a song. Its link (or a YouTube Music search URL) appears in Android's share sheet for an app you choose.
2. **Download with yt-dlp yourself**, e.g. in Termux on the same device:

   ```bash
   pkg install yt-dlp ffmpeg
   yt-dlp -x --audio-format mp3 "https://www.youtube.com/watch?v=…"
   ```

   (Those are yt-dlp's own options for MP3 extraction; see the [yt-dlp docs](https://github.com/yt-dlp/yt-dlp). Only download content you have the right to download — your own uploads, Creative-Commons or public-domain material, or where the rightsholder permits it.)
3. **Import:** back in Soundtrail, tap **♪** on that playlist song and pick the MP3. It now shows *On device* and plays offline in your music player — including when an automation triggers the playlist.

**Important:** Morphe patches a separate YouTube Music app; it is not an SDK or a third-party library API. Soundtrail can detect Morphe's patched package, open searches or the Library inside it, and receive song links shared back from it. Separately, an optional, explicit Google OAuth connection can use the official YouTube Data API to search videos found in accessible playlists. microG/GmsCore may support the standard sign-in flow on compatible setups, but it is not guaranteed and Soundtrail never reads microG or Morphe private storage. The official API does not reproduce the YouTube Music library: saved songs, albums, artists, mixes, and other Music-only collections may be unavailable. Soundtrail does not scrape YouTube, stream YouTube audio, or bypass subscriptions. Spotify and SoundCloud playback also stays in their own apps rather than being streamed by Soundtrail.

**SoundCloud access:** SoundCloud treats *every* API client as confidential, so its token exchange requires both a Client ID **and** a Client Secret — unlike Spotify, there is no public-client mode. Registering an API app requires an eligible SoundCloud subscription or an approved API request; check [SoundCloud for Developers](https://developers.soundcloud.com/docs/api/register-app). The Secret is stored only in Android Keystore-encrypted storage on this device.

**TheAudioDB free key:** Soundtrail calls TheAudioDB's v1 API with their documented shared free key (`123`, formerly `2`). The shared key can be rerouted or rate-limited at any time — an inactive route returns HTTP 404 with `{"Message":"Not found"}`. If it stops working, check [TheAudioDB's API page](https://www.theaudiodb.com/free_music_api); premium keys use the same URL format and only require changing `AUDIO_DB_FREE_KEY`.

## YouTube playlist search setup

The optional YouTube connection is independent of signing in to YouTube Music/Morphe. It uses Google's supported Android sign-in and requests the `https://www.googleapis.com/auth/youtube.readonly` scope for Data API access (no YouTube write scope); Google Sign-In handles the selected account identity. It does not inspect app data or extract credentials from microG.

Before connecting an account:

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Configure the OAuth consent screen (choose the appropriate audience and add your account as a test user while developing). Google may require verification or test-user access for the YouTube read-only scope, depending on the project and distribution.
3. Enable **YouTube Data API v3** in that project.
4. Under **APIs & Services → Credentials**, create an **Android OAuth client ID** for package `tech.egrie.soundtrail`. Add the SHA-1 signing-certificate fingerprint for the APK you install. This repository's shared debug key uses `21:D3:90:27:4A:69:5B:3F:C0:C3:84:A1:3E:70:F1:95:3A:A8:32:2F` (verify with `./gradlew :app:signingReport`, the `debug` variant); a build signed with a different certificate needs its own Android OAuth client/fingerprint. Do not create a Web client or put a client secret in the app.
5. On a compatible Google Play services or microG/GmsCore setup, ensure the Google account and OAuth APIs are configured. In Soundtrail's **Connect** tab, choose **Connect Google account**, review and approve the read-only permission, then choose **YouTube** on the **Search** tab.

Soundtrail validates the grant with a read-only `playlists.list` request. Search scans at most the first 50 of your playlists and the first 50 video items in each, then matches the terms locally and returns at most 20 results. This is deliberately bounded; older playlists/items beyond those pages won't be found. It searches playlist videos only, not the complete YouTube Music catalog or all saved Music-library types. If Google sign-in fails on microG, check the installed GmsCore version, account setup, package name/signing fingerprint, and Cloud OAuth/API configuration; Soundtrail does not bypass those checks.

## Build & install

1. Install Android Studio with JDK 17 and Android SDK Platform 35. Open this directory as a Gradle project.
2. Run the `app` configuration on an Android 8.0+ device/emulator, or run:

   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   Debug builds are signed with the key committed at `keystore/soundtrail-debug.p12` — the standard public debug credentials, `androiddebugkey` / `android` — rather than the per-machine `~/.android/debug.keystore`. Your build, the CI build, and every published release therefore share one signature, so a newer APK installs straight over an older one as an update. See *Updating an installed build* below for the one-time exception.

3. Optionally install Spotify, YouTube Music, and/or SoundCloud. An installed music app is **not** required for saving a pin; a browser can open supported links. For YouTube Music, configure microG and sign in inside your compatible music app if that is how your device is set up.
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

6. To use **Jellyfin**, install (or point at) your own Jellyfin server, open the **Connect** tab, and enter:

   - the **server address**, for example `http://192.168.1.20:8096` — plain HTTP is fine on your own network, HTTPS works too, and a sub-path behind a reverse proxy (`https://music.example.com/jellyfin`) is accepted;
   - a **username** and **password**. The password is sent once to that server and never stored. `GET /System/Info/Public` is checked first, so a wrong address or an unrelated web server is reported as such instead of looking like a bad password.

   Soundtrail then searches that library from the **Search** tab (pick the *Jellyfin* chip) and streams what you pin. Access is read-only: nothing is uploaded, renamed, or deleted, and the app never writes to the library.

7. For the optional **YouTube playlist search**, follow [YouTube playlist search setup](#youtube-playlist-search-setup), then connect Google in Soundtrail. To use the separate Morphe-patched YouTube Music app, install/configure Morphe and sign in there (including microG if that build uses it). Soundtrail can open the app's search or Library and receive shared song links; it never imports Morphe's session or reads private app data.

### Updating an installed build (signature mismatch)

Android will not install an APK over an existing app when the two were signed with different certificates, and reports:

```text
adb: failed to install soundtrail-X.Y.Z-debug.apk: Failure
[INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package tech.egrie.soundtrail signatures do not match newer version; ignoring!]
```

That means the installed copy carries a different signing key than the APK in your hand. The usual cause is an APK built before the shared debug key (`keystore/soundtrail-debug.p12`) existed: a CI runner is discarded after every job and Android's default debug key is created per machine, so each run of the release workflow signed its APK with a brand-new, randomly generated key. Those keys are gone and cannot be reproduced, so an update over such an install is impossible — uninstall once and install cleanly:

```bash
adb uninstall tech.egrie.soundtrail
adb install soundtrail-X.Y.Z-debug.apk
```

**The uninstall deletes the app's data.** Pins, playlists, automations, and imported audio live in private app storage with backups disabled (`allowBackup="false"`), so they disappear with the package. Stored logins go too: Spotify, SoundCloud, and Jellyfin tokens are encrypted with an Android Keystore key that the system destroys on uninstall, so reconnect your accounts afterwards. Before uninstalling, use **Connect → Export backup** to write your pins, playlists, and automations to a file; **Import backup** restores them on the new install (account logins and imported audio still need re-attaching).

It is a one-off. Every APK built from this repository — locally or by CI — is signed with `keystore/soundtrail-debug.p12`, so from the next release on, updates install over each other normally. That key is deliberately public (it is the conventional `androiddebugkey`, and anyone can read it in this repository), which is fine for sideloaded debug builds and unusable for a Play release — this repository intentionally configures no release signing.

## Releases

Every pull request **merged into `main`** bumps the version by one patch and publishes a release. The `Release on merge` workflow (`.github/workflows/release.yml`):

1. adds 1 to `versionCode` and `0.0.1` to `versionName` in `app/build.gradle.kts` (via `.github/scripts/bump_version.py`, which also skips any patch whose `vX.Y.Z` tag already exists),
2. builds `:app:assembleDebug` **before** pushing anything, so a broken build leaves `main` untouched,
3. commits `Bump version to X.Y.Z [skip ci]` to `main`, tags `vX.Y.Z`, and pushes both,
4. creates the GitHub Release `Soundtrail X.Y.Z` with generated notes (the merged pull requests) and the debug APK attached as `soundtrail-X.Y.Z-debug.apk`.

Details worth knowing:

- **It cannot loop.** The bump is pushed with the workflow's own token, and pushes made with `GITHUB_TOKEN` do not start further workflow runs.
- **Closing a pull request without merging does nothing**; the job is skipped unless `pull_request.merged` is true. A pull request merged into a branch other than `main` is ignored too.
- **Concurrent merges are serialized** by a `concurrency` group, so two merges cannot claim the same version number.
- **Branch protection:** the workflow needs to be allowed to push to `main`. If a rule requires pull requests for `main`, allow the GitHub Actions bot to bypass it (Settings → Branches → the `main` rule), otherwise the job fails with a message saying exactly that. The workflow requests `permissions: contents: write` for the same reason.
- **Manual runs:** the workflow also has `workflow_dispatch`, so *Actions → Release on merge → Run workflow* bumps and releases on demand (it always operates on `main`).
- The attached APK is the **debug** build, signed with the shared debug key committed at `keystore/soundtrail-debug.p12`, so each release installs as a normal update over the previous one (the first install over an APK built before that key existed still needs one uninstall — see *Updating an installed build* above). It is **not** a Play-ready build: that key is public and no release signing is configured here.
- The `Android build` workflow checks the APK it built against the committed keystore and fails if the signing certificate does not match, so the "updates install in place" guarantee cannot regress silently.
- CI note: because the bump is pushed with `GITHUB_TOKEN`, the separate `Android build` workflow does not re-run for that commit — the release workflow compiles the same code itself.

## Permissions, and why

| Permission | Used for |
| --- | --- |
| Internet | Provider search APIs, TheAudioDB lookups, your own Jellyfin server, and map tiles for the Pins map |
| Coarse/Fine location | Pinning, sorting pins, and the opt-in automation watch |
| Foreground service (location type) | The watch's persistent, visible automation service — opt-in only |
| Foreground service (media playback type) | Keeping a Jellyfin stream alive in the background under its playback notification |
| Post notifications | The watch status, automation triggers, and playback controls (Android 13+) |

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter coordinates manually; name the place and save. On the Pins tab, switch **List ↔ Map**: the map shows every pin in its provider colour — tap one for its card, pinch/double-tap to zoom, drag to pan, and **long-press anywhere to drop a new pin** at that exact spot.
- **Search:** connect Spotify or SoundCloud and switch the provider chip. For optional read-only YouTube playlist search, configure Google Cloud as described above, connect Google on the **Connect** tab, and choose **YouTube**; queries are matched locally against videos in your playlists. This does not cover the full YouTube Music library. The separate **YouTube Music** search field opens results in YouTube Music or Morphe; **Open your YouTube Music library** and share-back are available without OAuth. The **free TheAudioDB lookup** (artist + title, no account) and direct song-link paste are also available.
- **Share:** in Spotify, YouTube Music, or SoundCloud, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Jellyfin:** connect your server on the Connect tab, pick the *Jellyfin* chip on the Search tab, and pin what you find. Pinned Jellyfin songs and playlists play in the now-playing bar — and keep playing when you leave the app, with pause/stop on the notification, lockscreen, and connected Bluetooth controls. **⬇ Get** shares a link to the item in Jellyfin's own web client for anyone else on that server.
- **Backup:** on the Connect tab, **Export backup** to save everything local as a JSON file, and **Import backup** to restore one (additively) on this or another device.
- **Lists:** create a playlist, add songs (link optional), use **⬇ Get** to hand a link to your installed tools and **♪** to attach the resulting audio file for offline play, then turn on the watch. Create an automation — for example, *"Morning commute" → playlist "Drive" · while driving · 07:00–09:00* — and tap the notification when it fires.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `soundcloud.com/<artist>/<track>` (including `www.`/`m.` hosts and `/s-<token>` secret trails), and `on.soundcloud.com/<token>` or `soundcloud.app.goo.gl/<token>` share links. Tracking parameters are removed. Playlists, artist profiles, site pages, and unknown domains are rejected.

## Privacy and limitations

- Pins, playlists, and automations (song URLs, titles, notes, place names, coordinates, time windows) are saved in private app storage, not uploaded.
- Location is requested on demand for pins. The automation watch is a separate, explicit opt-in: a foreground service with a persistent notification that Android shows at all times while it runs. Turn the toggle off and every watch process stops.
- Spotify, SoundCloud, and Jellyfin tokens (plus SoundCloud's Client Secret and short-lived PKCE state) are stored with Android Keystore AES-GCM. The optional YouTube connection stores only an encrypted account label; Google services supplies an OAuth access token on demand, which Soundtrail holds in memory for the API request and never persists. Disconnect clears the local label and best-effort asks Google services to revoke the grant. Backups are disabled. Revoke access any time from your provider account settings.
- TheAudioDB lookups send the artist and title you type and store nothing. Lookup-offered links must pass the same URL allowlist as manual links.
- **Jellyfin playback can continue in the background.** The stream runs under a `mediaPlayback` foreground service with a MediaStyle notification (Android requires that visible notification — it is the control surface: play/pause and stop, plus lockscreen and Bluetooth transport controls through the registered media session). Nothing is downloaded or cached, and the access token travels in a request header rather than in a URL. Playback stops when you tap Stop — in the bar or on the notification — or disconnect the server; there is no queue and no next/previous track (one resolved item at a time), and the media session uses the platform's deprecated-but-supported `android.media.MediaSession` rather than a media library dependency.
- **Plain HTTP is permitted for the address you type yourself.** A home Jellyfin server usually speaks `http://`, and the platform's security config cannot know your server's address in advance, so `network_security_config.xml` allows cleartext traffic for the app. Soundtrail still contacts only the address you entered — every other provider API stays HTTPS-only, and certificates are validated against the system trust store with user-installed CAs never trusted.
- Soundtrail does **not** download, scrape, or re-host YouTube content. The optional YouTube API feature reads playlist/video metadata only; audio playback and the app handoff stay in YouTube Music, by design and per YouTube's Terms of Service.
- Motion detection is a GPS-speed estimate, not a sensor fusion product: expect misses at poor accuracy, and driving/running thresholds are approximate. Automations use cooldown-based re-arming (15 minutes), not edge triggering, so a rule can re-fire after its cooldown if conditions still hold.
- Without a location fix, use manual coordinates to create pins. **View place** still opens the pin in an external maps app or, with none installed, OpenStreetMap in a browser; the in-app map draws tiles itself and keeps them in memory only, so an area you have viewed works offline until the cache turns over, and a cold offline start shows markers over a blank canvas.
- SoundCloud permalinks are slug-based and canonicalized rather than re-derived; well-known non-artist paths (e.g. `/discover`, `/you/apps`) are rejected, but an unexpected new site section could in principle parse as a track and would simply open that page.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Unit tests cover URL allowlisting/canonicalization (Spotify, YouTube Music, SoundCloud, and Jellyfin item references), YouTube playlist/video response parsing and query filtering, TheAudioDB response parsing, Jellyfin server-address normalization, its authorization header and JSON payload shapes, library search request building/full URL encoding, library item parsing, stream URL construction (direct play plus transcode fallback), motion classification bands, automation condition evaluation (place radius, motion kind, time windows including midnight wrap) with cooldown-based triggering, YouTube Music search URL building, coordinate validation/distance, the RFC 7636 PKCE challenge, the backup document's round trip/refusal/entry-validation rules, playlist restore merging (additive, idempotent, never overwriting local edits), and the map's Web Mercator math (pixel/tile conversions, viewport fitting, antimeridian wrapping). The GitHub Actions Android workflow runs the same build and uploads a debug APK, and the `Release on merge` workflow builds, tags, and releases each merged pull request.

Documentation: [YouTube Data API v3](https://developers.google.com/youtube/v3) · [Google OAuth for Android](https://developers.google.com/identity/sign-in/android/start-integrating) · [Google Cloud Console](https://console.cloud.google.com/) · [Jellyfin API](https://api.jellyfin.org/) · [Jellyfin authentication](https://jellyfin.org/docs/general/administration/configuration/#security) · [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [SoundCloud API guide](https://developers.soundcloud.com/docs/api/guide) · [SoundCloud app registration](https://developers.soundcloud.com/docs/api/register-app) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [microG](https://microg.org/) · [OpenStreetMap](https://www.openstreetmap.org/copyright) · [CARTO map tiles](https://carto.com/developers/maps/)
