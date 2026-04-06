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
- MAL one-time authorization starts at `/auth/mal/start`
- JVM optimized for RPi4: 512MB heap
- SQLite database for local storage
- Logs to `./logs/mal2cy.log`

## API Endpoints

- Crunchyroll: Read/add/remove watchlist
- Crunchyroll validation: `/auth/crunchyroll/validate`
- MAL: Read/update anime list status
- Jikan: Fuzzy title matching for MAL IDs

## Future Features

- Watch history sync
- Custom list support
- Web UI for manual sync
