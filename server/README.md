# Soundtrail self-hosted sync and audio service

This service provides Soundtrail account sessions, an end-to-end-encrypted sync log with encrypted checkpoints, and an optional audio-only YouTube extraction endpoint. Deploy it on infrastructure you control, behind HTTPS.

## What the server can see

For cloud sync, the Android app encrypts each entity event and checkpoint on-device with AES-256-GCM. The server cannot decrypt pins, coordinates, playlist details, automation rules, song links, or the recovery key. It does see account email addresses, salted password hashes, session metadata, event IDs/sequences, ciphertext sizes, client IPs in proxy/server logs, and request timing.

The separate YouTube audio endpoint is **not end-to-end encrypted**: when used, the server receives a video ID, requests a public audio-only stream, and temporarily stores the resulting audio in a per-request directory under `/tmp` while returning it to the app. Temporary files are removed when the response finishes or fails. The server does not receive YouTube cookies, Google OAuth tokens, or a YouTube Music app session. Extraction uses the third-party `yt-dlp` project and is unofficial; it may break when YouTube changes. It does not handle private/age-restricted videos, logins, cookies, DRM, or access-control bypasses. Use it only for audio you own or have explicit permission to download, and follow the rules that apply to your content and location. Soundtrail asks the user to confirm permission before requesting extraction; the server cannot independently verify rights.

Keep the machine, database volume, TLS proxy, backups, logs, and `/tmp` storage secured. The database has a **64 MiB per-account quota** for encrypted sync events and checkpoints. Event rows are pruned into an encrypted client checkpoint after sync; retry-receipt metadata is also pruned at that point. Old delivery IDs may therefore be accepted again after compaction, but encrypted entity clocks and tombstones make duplicate old versions harmless. Snapshots are limited to 8 MiB.

The server applies in-process sliding-window limits to account creation, login, sync reads/writes, checkpoints, account deletion, and extraction; extraction also has a two-job concurrency cap. Current defaults are 5 account creations/hour/IP, 20 logins/minute/IP, 600 sync reads and writes/minute/IP, 10 checkpoints/minute/IP, 5 account deletions/hour/IP, and 30 extraction requests/minute/IP plus 20/minute/account. In-process limits reset on restart and are per instance, so keep the API behind a reverse proxy with its own request/body limits and rate limiting, especially if you run multiple workers. The app trusts `X-Forwarded-For` only when its direct peer is private/loopback; configure the proxy to overwrite or sanitize client-supplied forwarding headers before passing them upstream. There is no email verification or password-reset service. An authenticated account-deletion endpoint is available.

## Run with Docker Compose

1. Point a DNS name such as `sync.example.net` at your server.
2. Install Docker Engine and the Compose plugin.
3. Choose a reverse proxy that obtains and renews TLS certificates. For a Caddy container on the same Docker network, adapt [`Caddyfile.example`](Caddyfile.example); the upstream is `soundtrail-sync:8080`. If Caddy runs directly on the host, publish only to loopback and proxy `127.0.0.1:8080`.
4. Start the service:

   ```bash
   cd server
   docker compose up -d --build
   ```

The SQLite database lives in the named `soundtrail-sync-data` volume at `/data/soundtrail.sqlite3`. Back up that volume, test restoration, and keep an off-host copy. A consistent archive can be made with the service stopped; these commands use the container's attached volume, so they work regardless of Compose's generated volume name:

```bash
mkdir -p backups && chmod 700 backups
docker compose stop soundtrail-sync
cid=$(docker compose ps -aq soundtrail-sync)
docker run --rm --volumes-from "$cid" -v "$PWD/backups:/backup" alpine:3.20 \
  sh -c 'tar -czf /backup/soundtrail-data.tar.gz -C /data .'
docker compose start soundtrail-sync
```

To restore that archive, stop the service, keep a copy of the current archive/database first, and extract into the same volume:

```bash
cid=$(docker compose ps -aq soundtrail-sync)
docker compose stop soundtrail-sync
docker run --rm --volumes-from "$cid" -v "$PWD/backups:/backup:ro" alpine:3.20 \
  sh -c 'rm -f /data/soundtrail.sqlite3* && tar -xzf /backup/soundtrail-data.tar.gz -C /data'
docker compose start soundtrail-sync
```

Do not expose the app's port directly to the internet; use the HTTPS proxy. The `/health` endpoint reports liveness only. Extraction needs outbound network access to YouTube and enough temporary disk space for up to two concurrent 250 MiB downloads plus working files.

For a host-installed reverse proxy, the Compose file publishes the service as `127.0.0.1:8080`; for a proxy container on the same Docker network, remove the `ports` mapping and use the Compose service name instead. Never configure the Android client with the internal Docker hostname: enter the public `https://` URL of your proxy.

The container installs pinned `yt-dlp`, its default YouTube JavaScript components, a pinned Deno runtime, and FFmpeg for audio-stream assembly. Update these pins deliberately, rebuild, and test extraction after upstream changes. The extractor is optional in the app, but the same API image includes it.

## HTTP API

`/health` is a public liveness endpoint; the application endpoints are under `/v1`:

- `POST /accounts` — `{ "email": "…", "password": "…" }`, creates the account and returns `accountId`, `email`, `token`, and `expiresAt`.
- `POST /sessions` — same body, authenticates and returns a new bearer token.
- `DELETE /sessions/current` — revokes the current bearer session.
- `DELETE /account/current` — deletes the authenticated account, sessions, sync events, receipts, and checkpoint. This cannot be undone.
- `GET /events?after=<sequence>&limit=<1..500>` — returns opaque encrypted events, cursor information, and (when the caller is behind) the latest encrypted checkpoint plus events after it.
- `POST /events` — `{ "events": [{ "id": "UUID", "iv": "base64url", "ciphertext": "base64url" }] }`.
- `POST /snapshots` — stores a client-encrypted full-state checkpoint through a server cursor, then prunes covered event rows.
- `POST /youtube/audio` — authenticated JSON `{ "videoId": "…", "authorized": true }`; after the user's rights confirmation, returns a raw audio-only file. Only one YouTube video ID is accepted; playlists and video downloads are not supported.

Each account's cursor is independent and every sync read/write is scoped to that account. Checkpoints are encrypted and authenticated by the client; the server cannot inspect their contents. Event delivery is idempotent until a checkpoint compacts the corresponding receipt metadata. Passwords are stored as per-account salted scrypt hashes; random session tokens expire after 30 days and are stored server-side only as SHA-256 hashes.

## Configuration

- `PORT` — listen port (default `8080`).
- `SOUNDTRAIL_HOST` — listen address (default `0.0.0.0` inside the container).
- `SOUNDTRAIL_DB` — SQLite file (default `/data/soundtrail.sqlite3`).

The API intentionally has no password-reset or key-recovery endpoint. If a user loses their 256-bit recovery key, resetting the account password cannot recover encrypted content. Account deletion removes remote encrypted data; it does not erase the user's local device library. If the database is lost, only a database backup can restore the encrypted event log and checkpoints.

## Run the API tests

```bash
python3 -m unittest discover -s . -v
```
