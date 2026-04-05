# MAL2CY Sync

Bidirectional sync between MyAnimeList and Crunchyroll watchlists, optimized for Raspberry Pi 4.

## Setup

1. Clone or copy the project to your Raspberry Pi.
   - Java 21 is the supported runtime target for both local and Docker usage.

2. Configure environment variables:
   - Copy `.env.example` to `.env`
   - Fill in your MAL and Crunchyroll credentials
   - The app will persist refreshed MAL tokens to `./data/auth-tokens.json`
   - Register the MAL app with redirect URL `http://localhost:8080/auth/mal/callback`

3. Build and run with Docker:
   ```bash
   docker-compose up --build
   ```

4. Validate Crunchyroll auth before full sync:
   - Open `http://localhost:8080/auth/crunchyroll/validate`
   - Confirm you get `status: ok`, an `accountId`, and a `watchlistCount`

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
4. MAL redirects back to `http://localhost:8080/auth/mal/callback`.
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
