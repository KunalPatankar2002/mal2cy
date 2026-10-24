# MAL2CY Sync

Bidirectional sync between MyAnimeList and Crunchyroll watchlists, optimized for Raspberry Pi 4.

## Setup

1. Clone or copy the project to your Raspberry Pi.
   - Java 21 is the supported runtime target for both local and Docker usage.

2. Configure environment variables:
   - Copy `.env.example` to `.env`
   - Fill in your MAL and Crunchyroll credentials
   - The app will persist refreshed MAL tokens to `./data/auth-tokens.json`
   - Register the MAL app with the same URL you set in `MAL_REDIRECT_URI`

3. Build and run with Docker:
   ```bash
   docker-compose up --build
   ```

4. Validate Crunchyroll auth before full sync:
   - Open `http://localhost:8080/auth/crunchyroll/validate`
   - Confirm you get `status: ok`, an `accountId`, and a `watchlistCount`

## GitHub Actions Deployment

This repo includes a GitHub Actions workflow that:
- runs `mvn test`
- builds a Linux ARM64 Docker image
- pushes the image to GHCR
- deploys it using a self-hosted GitHub Actions runner on your Raspberry Pi

### Required GitHub Secrets

- `PI_APP_PATH`: absolute deploy path on the Pi, for example `/home/pi/mal2cy`
- `GHCR_USERNAME`: GitHub username used to read GHCR packages
- `GHCR_TOKEN`: GitHub token or PAT with `read:packages`

### Raspberry Pi Runner

Set up a self-hosted GitHub Actions runner on the Raspberry Pi for this repository. The deploy job runs directly on the Pi, so no public SSH access or port forwarding is required.

### Raspberry Pi Files

Put these in the deploy directory on the Pi:
- `.env` with your MAL and Crunchyroll values
- `docker-compose.deploy.yml` copied by the workflow
- writable `data/` and `logs/` directories

### Deployment Flow

1. Push to `main`.
2. GitHub Actions tests the project and publishes `ghcr.io/<owner>/<repo>:latest`.
3. The self-hosted runner on the Pi pulls the latest image and runs the Docker deploy.

You can also deploy manually on the Pi with:

```bash
export MAL2CY_IMAGE=ghcr.io/<owner>/<repo>:latest
docker compose -f docker-compose.deploy.yml pull
docker compose -f docker-compose.deploy.yml up -d
```

## Running Tests

To run all unit and integration tests:

```bash
mvn test
```

Tests are located in `src/test/java/com/mal2cy/` and cover authentication, watchlist fetch, and sync logic for both Crunchyroll and MAL.
The current test suite uses mocked network clients so it can run without live MAL/Crunchyroll credentials.

## One Time Crunchyroll Activation

So the `etp_rt` cookie never has to be copied by hand again:

1. Open `http://<pi-address>:8080/auth/crunchyroll/activate`. It returns a short `code`.
2. Within 5 minutes, enter that code at https://www.crunchyroll.com/activate (like setting up a smart TV).
3. Check `http://<pi-address>:8080/auth/crunchyroll/activate/status` shows `activated`.

The app then logs in like Crunchyroll's Android TV app, saving `crunchyrollTvRefreshToken` and `crunchyrollTvDeviceId` in `./data/auth-tokens.json` and renewing the login on every sync. If that login ever fails, the app logs an error and falls back to `CRUNCHYROLL_ETP_RT`. The TV client id is unofficial; if Crunchyroll retires it, set `CRUNCHYROLL_TV_CLIENT_AUTH` (and optionally `CRUNCHYROLL_TV_USER_AGENT`) to a current one.

## One Time MAL Setup

1. Start the app locally.
2. Open `http://localhost:8080/auth/mal/start` in your browser.
3. Sign in to MAL and approve the app.
4. MAL redirects back to the URL configured in `MAL_REDIRECT_URI`.
5. The app exchanges the authorization code and stores the tokens in `./data/auth-tokens.json`.

After that first authorization, the app will refresh MAL tokens automatically.

## Configuration

- Sync runs daily at midnight (configurable in `application.yml`)
- Scheduler uses the configured `app.sync.zone` time zone
- MAL access tokens are refreshed automatically with the configured refresh token
- Refreshed MAL tokens are stored at the configured `app.auth.token-store-path`
- Crunchyroll watch-history cursor is stored in the same local state file as `crunchyrollWatchHistoryCursor`
- Crunchyroll logins use the activated TV login when present (see One Time Crunchyroll Activation). Otherwise they use the `etp_rt` cookie: a rotated cookie saved as `crunchyrollEtpRt` first, then `CRUNCHYROLL_ETP_RT`. The web client can't renew itself, so without activation an expired cookie has to be copied into `.env` again
- Sync failures are logged to `./logs/mal2cy.log` with their stack trace, and a failed lookup for one title is skipped instead of aborting the whole run
- MAL one-time authorization starts at `/auth/mal/start`
- JVM optimized for RPi4: 512MB heap
- SQLite database for local storage
- Logs to `./logs/mal2cy.log`

## API Endpoints

- Crunchyroll: Read/add/remove watchlist
- Crunchyroll: Read watch history for fully watched episode progress
- Crunchyroll validation: `/auth/crunchyroll/validate`
- MAL: Read/update anime list status and watched episode progress
- Jikan: Fuzzy title matching for MAL IDs

## Watch History Sync

The sync now reads Crunchyroll watch history from:

```text
GET /content/v2/{accountId}/watch-history?page_size=100&locale=en-US
```

Trusted fields for MAL progress sync:
- `fully_watched`
- `date_played`
- `panel.episode_metadata.series_id`
- `panel.episode_metadata.series_title`
- `panel.episode_metadata.episode_number`

Current behavior:
- only `fully_watched=true` entries count toward MAL progress
- entries without an integer `episode_number` are ignored
- only the first page (`page_size=100`) is read in v1
- the sync replays a 48-hour overlap window from the stored watch-history cursor to avoid missing delayed writes
- for each Crunchyroll series, the highest fully watched episode number wins
- MAL is updated only when Crunchyroll advances beyond MAL's current watched episode count
- MAL status is set to `watching`, or `completed` when the watched count reaches the MAL total episode count

The application logs accepted watch-history items, skipped regressions, missing mappings, truncation warnings, and cursor advancement in `./logs/mal2cy.log`.

## Notifications

Set `NTFY_TOPIC` in `.env` to a hard-to-guess name and subscribe to the same topic in the [ntfy](https://ntfy.sh) phone app. You get a notification when:
- a sync fails (with the error)
- the activated Crunchyroll TV login is rejected and needs re-activating
- a sync changed something or had per-title errors (a one-line summary)

## Title Matching

- Crunchyroll watchlist titles are matched against your own MAL list first (main, English and alternative titles). Jikan is only a fallback: it is throttled to about one request a second, retried on HTTP 429, and a result is only accepted when one of its titles matches.
- MAL sequels ("2nd Season", "Season 3", "Part 2", "II", "...-hen" arcs) map to the parent Crunchyroll series, since Crunchyroll keeps every season under one series. When several MAL seasons share a series, watch history goes to the season that is `watching` on MAL. A Crunchyroll episode number above the MAL episode count is skipped.
- Titles with no match are remembered in `./data/match-misses.json` and searched again after 30 days (`app.match.retry-days`).
- Hand-written matches go in `./data/title-overrides.json` (created empty on first run):
  ```json
  { "malToCrunchyroll": { "41025": "GG5H5XMWV" }, "crunchyrollToMal": { "GT00371893": "38668" } }
  ```
- `GET /sync/report` lists matched and unmatched titles and the last sync result. `POST /sync/run` runs a sync now. Set `APP_SYNC_DRY_RUN=true` to log what a sync would write without changing MAL or Crunchyroll.

## Future Features

- Custom list support
- Web UI for manual sync
