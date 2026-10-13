# Soundtrail — music for the places you love

An Android app that saves **Spotify**, **YouTube Music**, **SoundCloud**, and **your own Jellyfin server's** songs as private, location-based pins, with an **in-app map**, offline-audio management, local playlists, opt-in place/motion/time automations, **JSON backup/export**, optional **self-hosted end-to-end encrypted cloud sync**, and background Jellyfin playback with system media controls.

## What connects, and how

| Service | Integration |
| --- | --- |
| Spotify | Authorization Code **with PKCE** (no client secret). Search for tracks with the official Web API; tap a pin to open playback in Spotify or a browser. Tokens are encrypted with Android Keystore. |
| SoundCloud | Authorization Code **with PKCE** (OAuth 2.1) against `secure.soundcloud.com`. Search for tracks with the official API; tap a pin to open playback in SoundCloud or a browser. Tokens **and the Client Secret** are encrypted with Android Keystore. Requires an API app on SoundCloud's side — see below. |
| Jellyfin (your own server) | Username + password sign-in against **your** server, read-only music-library search, in-app/background playback, and offline download through Jellyfin's authenticated `/Items/{id}/Download` route. The server enforces the signed-in account's access; Soundtrail downloads only items that account is allowed to retrieve, and caps each transfer at 250 MiB. The password is never stored; the access token is encrypted with Android Keystore. Works over HTTPS or plain HTTP on a trusted home network. |
| Song lookup (TheAudioDB) | **Free, no account.** Enter artist + title to get exact names, album, genre/mood, and — when TheAudioDB knows one — a Spotify or YouTube link that passes the same allowlist. Metadata only; nothing is stored. |
| YouTube Music app | Search and Library links are handed to the installed YouTube Music app, including Morphe's patched package, or to music.youtube.com. Share a song link back to Soundtrail to pin it. Soundtrail never reads the music app's private storage or session. |
| YouTube playlist search (optional) | Explicit Google Sign-In requests the `youtube.readonly` scope and calls the official YouTube Data API v3 for videos in your own playlists. The OAuth access token is requested from Google services when needed, held in memory for API calls, and never persisted by Soundtrail. A compatible microG/GmsCore installation may provide the required sign-in APIs, but support depends on its version, device, account, package certificate, and OAuth setup. This does **not** expose the full YouTube Music library (saved songs, albums, mixes, etc.); see setup and scope limits below. |
| YouTube links | The official YouTube Data API integration is read-only metadata access. A separate, optional audio-only extraction action uses the self-hosted server, has no YouTube login/cookies, and must only be used for content you own or have permission to download. |
| Your audio files | Download eligible Jellyfin tracks or permitted public YouTube audio for offline playback, or attach audio you already own or are authorized to use. Files are copied into private app storage; audio is not cloud-synced. |
| Map tiles | The Pins tab's **Map** view draws an OpenStreetMap-based map on-device: raster tiles are fetched from CARTO's public tile server (`basemaps.cartocdn.com`) for the visible area only. Tile requests reveal the visible map area; pin coordinates are rendered locally and are not included in tile requests. Attribution stays on screen. |
| Backup files | **Export** writes pins, playlists, and automations to a JSON file you choose; **Import** reads one back additively. The backup file goes only where you put it. Logins and audio files are excluded. |
| Optional cloud sync | Connect to a self-hosted Soundtrail API over HTTPS. Pins (including coordinates), playlists, song links, and automations sync two-way, including edits and deletions. Events and compacted checkpoints are encrypted on-device with AES-256-GCM. The recovery key is user-held and cannot be reset by changing the account password. |
| Location & motion | `LocationManager` on demand, plus an **opt-in** foreground watch service for automations. Motion (driving/running/walking) is inferred **on-device from GPS speed** — no Play Services, no Activity Recognition API, no advertising ID. |
| Time | The watch checks the current time every minute; automations can fire only inside a time window (wraps midnight). The Lists tab shows the live clock. |

## Playlists & automations

- **Playlists** (Lists tab) are named groups stored locally by default. Optional cloud sync encrypts playlist metadata and song links before upload. A song needs a title; supported provider links are optional — without one, tapping play opens a YouTube Music search in the installed app or browser.
- **Jellyfin songs** are stored as *item references* (`jellyfin:item:<id>`), never as server URLs: addresses and access tokens do not enter pins, playlists, backups, or cloud-sync records. Playback resolves against the current connection; direct play is tried first, with an automatic MP3 transcode fallback. Download eligible audio through Jellyfin's authenticated endpoint; its account permissions remain authoritative. **YouTube audio** can be requested through the self-hosted API for public audio-only streams, with an explicit rights confirmation; that extraction is unofficial and does not use YouTube login cookies. Both kinds of offline audio stay on-device and are not backed up or cloud-synced.
- **Backups:** export pins, playlists, and automations to a JSON file, or import additively. Backups and cloud sync are separate: backups are readable JSON; cloud sync sends only encrypted event envelopes. Account logins and local audio files stay on-device; restore or sync may require reconnecting providers or downloading local audio again.
- **Get a song (external handoff):** the ⬇ action for other providers shares the song link or YouTube Music search URL. YouTube playlist items also offer a separate, permission-confirmed audio-only extraction action; Jellyfin items use their authenticated offline-download action.
- **Import audio:** the ♪ button attaches an audio file from your device to a playlist song. The file is copied into private storage and shows as *On device*; tapping play opens it in your music player, fully offline. Removing the song or playlist deletes the copy.
- **Offline storage management:** Lists shows total local audio storage; **Manage** opens a largest-first file list with per-file sizes and playlist details. Remove individual copies or clear all offline files; playlist entries are kept. Removing a song or playlist still deletes its attached file.
- **Automations** point a playlist at conditions: *near a saved spot (20–2000 m radius)*, *while walking / running / driving*, and/or *between two times of day*. Set any combination; all set conditions must hold at once.
- **The watch**: an opt-in foreground service (persistent notification — required by Android and kept honest by it) checks location, motion, and time. When a rule matches it posts a notification with **Play playlist** and **Open Soundtrail** actions. Nothing plays without a tap, and a rule re-arms after a 15-minute cooldown.
- **Motion inference**: GPS speed bands — under 0.5 m/s stationary, to 2.6 walking, to 7.5 running (covers cycling), above that driving. It is an estimate; tunnels and poor GPS delay or miss triggers.
- **Battery**: continuous GPS watching uses noticeably more battery. The watch runs only while its toggle is on and its notification is visible.
- **Privacy**: by default, pins, coordinates, playlists, automations, and imported audio stay on the device. If you opt in to cloud sync, pins (including coordinates), playlist metadata, song links, and automation rules are encrypted before leaving the phone; the server stores ciphertext and account/session metadata for sync. The separate YouTube extractor receives the requested video ID and temporarily processes its audio. Downloaded audio and provider credentials are never synced. Map tiles reveal only the visible area, not your saved pin coordinates. For YouTube playlist search, Soundtrail fetches metadata and matches your query on-device; it does not send the query to YouTube.

## Offline audio and source permissions

The optional YouTube offline action is **audio-only** and runs through your self-hosted server; it requires a signed-in Soundtrail sync-server account. The official YouTube Data API remains read-only and does not provide media bytes. The extractor uses no Google OAuth token, YouTube Music session, or browser cookies; it only attempts a publicly accessible audio stream, so private, age-restricted, login-gated, DRM-protected, or otherwise restricted items will fail. You must confirm that you own the content or have explicit permission to download it. This unofficial extractor may stop working when YouTube changes its site. The download UI distinguishes server-side preparation from transfer to the device and shows transfer progress. The server temporarily handles the video ID and audio file; the audio is streamed to the app, saved privately, and not added to cloud sync.

Jellyfin offline downloads remain authenticated against your own server and subject to its account permissions. Both download paths are limited to 250 MiB per track. The **Get** action for other links still hands the link to an app you choose; Soundtrail does not bypass access controls or download content you do not have rights to keep.

## YouTube Music and official API access

Morphe patches a separate YouTube Music app; it is not an SDK or a third-party library API. Soundtrail can detect Morphe's patched package, open searches or the Library inside it, and receive song links shared back from it. Separately, an optional, explicit Google OAuth connection can use the official YouTube Data API to search videos found in accessible playlists. microG/GmsCore may support the standard sign-in flow on compatible setups, but it is not guaranteed and Soundtrail never reads microG or Morphe private storage. The official API does not reproduce the YouTube Music library: saved songs, albums, artists, mixes, and other Music-only collections may be unavailable. The optional audio extractor is a separate, unofficial server-side component; it uses no Google token or YouTube Music session, supports public audio-only streams, and must be used only with content you are authorized to download. It does not bypass logins, DRM, paywalls, or access restrictions. Spotify and SoundCloud playback also stays in their own apps rather than being streamed by Soundtrail.

## Self-hosted cloud sync

Cloud sync is opt-in and only available when you deploy the API from [`server/`](server/README.md) and enter its public HTTPS URL on the Connect tab. The server manages email/password accounts and sessions; it has no key recovery service. The app generates a random 256-bit recovery key on account creation. **Save it somewhere private before relying on sync.** You may reveal it again on an already signed-in device, but a new device needs the key. A password reset cannot recover encrypted content, and uninstalling removes the device's saved copy of the key.

Each pin, playlist, playlist song, and automation is encrypted client-side with AES-256-GCM. The server stores opaque ciphertext envelopes and sequence metadata; it does not receive the recovery key or plaintext sync data. Changes flow both ways, including per-item additions, edits, and deletions. Concurrent versions merge deterministically using a last-write-wins clock and device-id tie-breaker. Once history grows, the client uploads an encrypted checkpoint and the server prunes covered event rows. Provider tokens and downloaded audio files are not synced.

The server sees account email, authentication metadata, request timing, event IDs, and ciphertext sizes. If you use YouTube extraction, it also processes the requested video ID and transient audio file. Run it only behind HTTPS, protect and back up its SQLite volume, and keep its host patched. The server includes per-process request rate limits, a per-account encrypted-sync quota, and authenticated account deletion, but no email verification or password-reset service; use reverse-proxy limits as well, especially with multiple instances. See [`server/README.md`](server/README.md).

Automatic sync is **off by default**. Enable **Sync automatically when Soundtrail opens** in Connect to sync at app launch; it requires a signed-in account and this device's saved recovery key and does not run while the app is closed. Connect also shows pending local changes; after a sync failure, **Retry sync** runs the same merge again. In the same section, **Delete account and remote sync data** removes the server account/history only; local pins, playlists, automations, and offline audio remain on the device.

### Deploy

1. Deploy [`server/docker-compose.yml`](server/docker-compose.yml) and a TLS reverse proxy for a domain you control. See [`server/Caddyfile.example`](server/Caddyfile.example).
2. From the repository, run `cd server && docker compose up -d --build`. The service binds to loopback for a host proxy by default; do not expose port 8080 directly.
3. In Soundtrail, open **Connect → Self-hosted cloud sync**, enter the public `https://` URL, and create an account. Save the recovery key, then tap **Sync now**. On another device, sign in to the same server and enter the saved key.

Backups remain useful as a separate portable JSON export; they are not encrypted cloud sync.



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

   Soundtrail searches that library from the **Search** tab (pick the *Jellyfin* chip), streams what you pin, and can save an eligible track for offline playback using Jellyfin's authenticated download endpoint. Soundtrail never writes to, renames, or deletes items in the server library. Download permission is decided by Jellyfin; only save media you have rights to access and keep.

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

**The uninstall deletes this device's app data.** Local pins, playlists, automations, downloaded/imported audio, sync session, and the device's encrypted copy of the recovery key disappear with the package. Synced ciphertext remains on your self-hosted server, but you must have separately saved the recovery key to decrypt it after reinstalling. Stored provider logins go too; reconnect those accounts afterwards. Before uninstalling, export a portable JSON backup if desired and make sure your cloud recovery key is saved somewhere private.

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
| Internet | Provider search APIs, TheAudioDB lookups, your own Jellyfin server, optional HTTPS sync server, and map tiles |
| Coarse/Fine location | Pinning, sorting pins, and the opt-in automation watch |
| Foreground service (location type) | The watch's persistent, visible automation service — opt-in only |
| Foreground service (media playback type) | Keeping a Jellyfin stream alive in the background under its playback notification |
| Post notifications | The watch status, automation triggers, and playback controls (Android 13+) |

## Try it

- **Explore:** tap **Drop a pin**. Enter a song title and a supported link. Use **Locate** or enter coordinates manually; name the place and save. On the Pins tab, switch **List ↔ Map**: the map shows every pin in its provider colour — tap one for its card, pinch/double-tap to zoom, drag to pan, and **long-press anywhere to drop a new pin** at that exact spot.
- **Search:** connect Spotify or SoundCloud and switch the provider chip. For optional read-only YouTube playlist search, configure Google Cloud as described above, connect Google on the **Connect** tab, and choose **YouTube**; queries are matched locally against videos in your playlists. This does not cover the full YouTube Music library. The separate **YouTube Music** search field opens results in YouTube Music or Morphe; **Open your YouTube Music library** and share-back are available without OAuth. The **free TheAudioDB lookup** (artist + title, no account) and direct song-link paste are also available.
- **Share:** in Spotify, YouTube Music, or SoundCloud, share a **song** link to Soundtrail to open a prefilled pin editor.
- **Jellyfin:** connect your server on the Connect tab, pick the *Jellyfin* chip on Search, and add its songs to a pin or playlist. Stream in-app or in the background with notification/lockscreen controls. In a playlist, tap the download icon to save an eligible track privately for offline playback; Jellyfin decides whether your account may download it.
- **Backup and sync:** export/import a portable JSON backup, or deploy your own HTTPS server and opt in to two-way encrypted cloud sync from **Connect → Self-hosted cloud sync**. Save the recovery key before using another device.
- **Lists:** create a playlist and add songs. Jellyfin items can be downloaded offline with the download action; use **♪** to attach an audio file you already own. Other **⬇ Get** actions only hand a link to another app. Turn on the watch and create an automation — for example, *"Morning commute" → playlist "Drive" · while driving · 07:00–09:00* — then tap the notification when it fires.
- Supported links: `open.spotify.com/track/<id>`, `spotify:track:<id>`, `music.youtube.com/watch?v=<id>`, `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `soundcloud.com/<artist>/<track>` (including `www.`/`m.` hosts and `/s-<token>` secret trails), and `on.soundcloud.com/<token>` or `soundcloud.app.goo.gl/<token>` share links. Tracking parameters are removed. Playlists, artist profiles, site pages, and unknown domains are rejected.

## Privacy and limitations

- By default, pins, playlists, and automations (including coordinates and song links) are saved only in private app storage. Optional cloud sync sends their client-encrypted event envelopes to the self-hosted server you choose; for sync it sees account/session metadata and ciphertext sizes, but not item contents or the recovery key. If you invoke YouTube extraction, the server receives that video's ID and temporarily handles the downloaded audio. Provider tokens and downloaded audio are never synced.
- Location is requested on demand for pins. The automation watch is a separate, explicit opt-in: a foreground service with a persistent notification that Android shows at all times while it runs. Turn the toggle off and every watch process stops.
- Spotify, SoundCloud, and Jellyfin tokens (plus SoundCloud's Client Secret and short-lived PKCE state) are stored with Android Keystore AES-GCM. The optional YouTube connection stores only an encrypted account label; Google services supplies an OAuth access token on demand, which Soundtrail holds in memory for the API request and never persists. Disconnect clears the local label and best-effort asks Google services to revoke the grant. Backups are disabled. Revoke access any time from your provider account settings.
- TheAudioDB lookups send the artist and title you type and store nothing. Lookup-offered links must pass the same URL allowlist as manual links.
- **Jellyfin streaming playback can continue in the background.** Streams run under a `mediaPlayback` foreground service with a MediaStyle notification and lockscreen/Bluetooth controls. Streaming itself is not cached; a separate explicit playlist download saves eligible audio privately, capped at 250 MiB per item and subject to Jellyfin's access rules. The access token travels in a request header, not in a URL. There is no queue or next/previous track, and the media session uses the platform's deprecated-but-supported `android.media.MediaSession`.
- **Plain HTTP is permitted for the address you type yourself.** A home Jellyfin server usually speaks `http://`, and the platform's security config cannot know your server's address in advance, so `network_security_config.xml` allows cleartext traffic for the app. Soundtrail still contacts only the address you entered — every other provider API stays HTTPS-only, and certificates are validated against the system trust store with user-installed CAs never trusted.
- The official YouTube Data API feature is read-only metadata access; YouTube Music playback stays in its app. A separate, optional, unofficial self-hosted extractor can request audio-only streams for public videos after a rights confirmation. It uses no YouTube/Morphe session or cookies, does not handle access-restricted media, and may stop working if YouTube changes its site. Jellyfin downloads use the server's official authenticated endpoint and remain subject to your rights and the server's permissions.
- Motion detection is a GPS-speed estimate, not a sensor fusion product: expect misses at poor accuracy, and driving/running thresholds are approximate. Automations use cooldown-based re-arming (15 minutes), not edge triggering, so a rule can re-fire after its cooldown if conditions still hold.
- Without a location fix, use manual coordinates to create pins. **View place** still opens the pin in an external maps app or, with none installed, OpenStreetMap in a browser; the in-app map draws tiles itself and keeps them in memory only, so an area you have viewed works offline until the cache turns over, and a cold offline start shows markers over a blank canvas.
- SoundCloud permalinks are slug-based and canonicalized rather than re-derived; well-known non-artist paths (e.g. `/discover`, `/you/apps`) are rejected, but an unexpected new site section could in principle parse as a track and would simply open that page.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover -s server -v
```

Unit tests cover the existing URL/provider, backup, map, playlist, and automation behavior plus Jellyfin download validation, YouTube video-ID parsing, recovery-key encoding, AES-GCM event encryption, tamper rejection, deterministic tombstone merges, and sync-payload handling (including keeping local audio filenames out of ciphertext). Python `unittest` coverage exercises account/session isolation and deletion, opaque event pagination/idempotency, checkpoint compaction, rate limits including trusted-proxy IP handling, and authorized audio-route validation/stream cleanup. The GitHub Actions Android workflow runs the Android build and uploads a debug APK.

Documentation: [YouTube Data API v3](https://developers.google.com/youtube/v3) · [Google OAuth for Android](https://developers.google.com/identity/sign-in/android/start-integrating) · [Google Cloud Console](https://console.cloud.google.com/) · [Jellyfin API](https://api.jellyfin.org/) · [Jellyfin authentication](https://jellyfin.org/docs/general/administration/configuration/#security) · [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow) · [SoundCloud API guide](https://developers.soundcloud.com/docs/api/guide) · [SoundCloud app registration](https://developers.soundcloud.com/docs/api/register-app) · [TheAudioDB free music API](https://www.theaudiodb.com/free_music_api) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [microG](https://microg.org/) · [OpenStreetMap](https://www.openstreetmap.org/copyright) · [CARTO map tiles](https://carto.com/developers/maps/)
