# Soundtrail self-hosted sync and audio service

This service provides Soundtrail account sessions, an end-to-end-encrypted sync log with encrypted checkpoints, and an optional audio-only YouTube extraction endpoint. Deploy it on infrastructure you control, behind HTTPS.

## What the server can see

For cloud sync, the Android app encrypts each entity event and checkpoint on-device with AES-256-GCM. The server cannot decrypt pins, coordinates, playlist details, automation rules, song links, or the recovery key. It does see account email addresses, salted password hashes, session metadata, event IDs/sequences, ciphertext sizes, client IPs in proxy/server logs, and request timing.

The separate YouTube audio endpoint is **not end-to-end encrypted**: when used, the server receives a video ID, requests a public audio-only stream, and temporarily stores the resulting audio in a per-request directory under `/tmp` while returning it to the app. Temporary files are removed when the response finishes or fails. The server does not receive YouTube cookies, Google OAuth tokens, or a YouTube Music app session. Extraction uses the third-party `yt-dlp` project and is unofficial; it may break when YouTube changes. It does not handle private/age-restricted videos, logins, cookies, DRM, or access-control bypasses. Use it only for audio you own or have explicit permission to download, and follow the rules that apply to your content and location. Soundtrail asks the user to confirm permission before requesting extraction; the server cannot independently verify rights.

Keep the machine, database volume, TLS proxy, backups, logs, and `/tmp` storage secured. The database has a **64 MiB per-account quota** for encrypted sync events and checkpoints. Event rows are pruned into an encrypted client checkpoint after sync; retry-receipt metadata is also pruned at that point. Old delivery IDs may therefore be accepted again after compaction, but encrypted entity clocks and tombstones make duplicate old versions harmless. Snapshots are limited to 8 MiB.

The server applies in-process sliding-window limits to account creation, login, sync reads/writes, checkpoints, account deletion, and extraction; extraction also has a two-job concurrency cap. Current defaults are 5 account creations/hour/IP, 20 logins/minute/IP, 600 sync reads and writes/minute/IP, 10 checkpoints/minute/IP, 5 account deletions/hour/IP, and 30 extraction requests/minute/IP plus 20/minute/account. In-process limits reset on restart and are per instance, so keep the API behind a reverse proxy with its own request/body limits and rate limiting, especially if you run multiple workers. The app trusts `X-Forwarded-For` only when its direct peer is private/loopback; configure the proxy to overwrite or sanitize client-supplied forwarding headers before passing them upstream. There is no email verification or password-reset service. An authenticated account-deletion endpoint is available.

## Run natively (no Docker, any OS and CPU)

The server is plain Python. It runs on Linux, macOS, Windows, and BSD, on x86-64, ARM64, and other CPUs with a Python 3.10+ interpreter. Docker is not required.

1. Point a DNS name such as `sync.example.net` at your server.
2. Install Python 3.10 or newer (`python3 --version`, or `py -3 --version` on Windows).
3. Optional, for YouTube audio extraction only: install [FFmpeg](https://ffmpeg.org/download.html) and make `ffmpeg` available on `PATH`.
4. Start the service from the `server` folder:

   ```bash
   cd server
   python3 run.py          # Windows: py -3 run.py
   ```

   `run.py` creates a private `.venv`, installs the pinned dependencies, and starts the API. The first run downloads packages; later runs start immediately. Other commands:

   - `python3 run.py --setup` prepares the environment without starting the server.
   - `python3 run.py --test` runs the API test suite.

5. Put a reverse proxy with TLS in front of it. The API listens on `127.0.0.1:8080` by default. Keep that loopback default and proxy to it, for example with [`Caddyfile.example`](Caddyfile.example) (Caddy runs on the same machine, so the upstream is `127.0.0.1:8080`). Do not expose port 8080 directly.

The SQLite database is created at `server/data/soundtrail.sqlite3`. Set `SOUNDTRAIL_DB` to store it elsewhere (for example on a dedicated disk).

### Run as a background service

Use your platform's service manager so the API restarts after reboots. Run it as a dedicated, unprivileged user.

**Linux (systemd)**: save as `/etc/systemd/system/soundtrail-sync.service`, adjusting `User`, `WorkingDirectory`, and the paths:

```ini
[Unit]
Description=Soundtrail encrypted sync API
After=network-online.target
Wants=network-online.target

[Service]
User=soundtrail
WorkingDirectory=/opt/soundtrail/server
Environment=SOUNDTRAIL_HOST=127.0.0.1
Environment=PORT=8080
Environment=SOUNDTRAIL_DB=/var/lib/soundtrail/soundtrail.sqlite3
ExecStart=/usr/bin/python3 /opt/soundtrail/server/run.py
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then run `sudo systemctl daemon-reload && sudo systemctl enable --now soundtrail-sync`.

**macOS (launchd)** and **Windows** (Task Scheduler, or a service wrapper such as NSSM): run `python3 run.py` (or `py -3 run.py`) from the `server` folder at boot, with the working directory set to `server`, and set `SOUNDTRAIL_HOST`/`SOUNDTRAIL_DB` as environment variables for that task.

### Back up the database

Back up the database, not a live copy. Stop the service, then archive the whole `data` folder (the database plus its WAL side-files):

```bash
# Linux/macOS, from the server folder, with the service stopped
mkdir -p backups && chmod 700 backups
tar -czf backups/soundtrail-data.tar.gz -C . data
```

On Windows, stop the task and copy the `data` folder to a safe location. To restore, stop the service, keep a copy of the current folder, and replace the `data` folder (or the `SOUNDTRAIL_DB` location) with the backup contents. Test restoration, and keep an off-host copy.

The `/health` endpoint reports liveness only. Extraction needs outbound network access to YouTube and enough temporary disk space for up to two concurrent 250 MiB downloads plus working files.

### Dependencies

- `requirements.txt` — `yt-dlp` (pure Python), used only by the optional YouTube audio endpoint.
- `requirements-js.txt` — `deno`, a JavaScript runtime for yt-dlp's YouTube challenge solver. It is optional: Deno publishes wheels for Linux x86-64/ARM64 (glibc), macOS x86-64/ARM64, and Windows x86-64 only. On other platforms `run.py` skips it; install Deno or Node.js yourself and it will be used from `PATH`.
- FFmpeg — installed separately (see step 3).

Update the pins deliberately, then re-test extraction after upstream changes.

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
- `SOUNDTRAIL_HOST` — listen address (default `127.0.0.1`; keep it on loopback behind a TLS proxy).
- `SOUNDTRAIL_DB` — SQLite file (default `server/data/soundtrail.sqlite3`).

The API intentionally has no password-reset or key-recovery endpoint. If a user loses their 256-bit recovery key, resetting the account password cannot recover encrypted content. Account deletion removes remote encrypted data; it does not erase the user's local device library. If the database is lost, only a database backup can restore the encrypted event log and checkpoints.

## Run the API tests

```bash
python3 run.py --test   # or: python3 -m unittest discover -s . -v
```
