# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Clean build artifacts
./gradlew clean
```

> **Note**: `gradle.properties` points to a custom JBR at `C:/Users/sdavila.AD/Documents/HassiSiteApp/custom_jbr` and includes SSL truststore workarounds. If builds fail on a new machine, update `org.gradle.java.home` or remove it to use system Java 17.

## Architecture

**MVVM + Repository + Service Locator** — no Dagger/Hilt. All singletons are constructed and held by `ServiceLocator.kt`, which is initialized from `AtlasApp` (the custom `Application` class).

### Dependency flow

```
UI (Fragment / Activity)
  → ServiceLocator  (provides lazy singletons)
  → Services        (ClockingService, SyncService, IncidentService, RulesService)
  → Repositories    (AuthRepository, ConfigRepository)
  → DAOs + ApiClient
  → Room DB  /  Retrofit
```

### Key files

| File | Role |
|------|------|
| `ServiceLocator.kt` | Central DI: lazy-creates every singleton |
| `AtlasApp.kt` | Application class; initialises Room, stores global reference |
| `ApiClient.kt` | Retrofit wrapper; resolves primary vs. fallback URL via `/health` ping; injects JWT + Device-ID via OkHttp interceptor |
| `AuthRepository.kt` | JWT login, storage, expiry; supports `mock.` prefix tokens |
| `ClockingService.kt` | Core access-control logic — anti-bounce (30 s), ENTRY/EXIT/AUTO resolution, session management |
| `SyncService.kt` | Full sync orchestration: device registration → download master data → upload logs/incidents/sessions |
| `DataWedgeManager.kt` | Honeywell DataWedge barcode scanner via broadcast intents; emits via Kotlin `Flow` |
| `data/db/Entities.kt` | All 11 Room entities in one file |
| `data/db/dao/Daos.kt` | All DAOs in one file |
| `network/dto/Dtos.kt` | All Retrofit DTOs in one file |

### Database (Room — 11 tables)

`config`, `projects`, `zones`, `contractors`, `persons`, `access_points`, `crypto_keys`, `revoked_tokens`, `access_logs`, `incidents`, `work_sessions`.

Unsynced records are flagged with a `synced = false` column; `SyncService` uploads them and flips the flag.

### API endpoints (`AtlasApiService.kt`)

- `POST /v1/Auth/login`
- `GET  /api/trac/sync/download` — master data + workers
- `POST /api/trac/sync/upload` — access logs
- `POST /api/trac/sync/upload-incidents`
- `POST /api/trac/sync/upload-sessions`
- `POST /api/trac/sync/register-device`
- `GET  /health` — URL health check

Default primary URL: `https://web-atlas-api-dev.azurewebsites.net`. Fallback: `http://localhost:5000`. Both are configurable from `SettingsFragment`.

### UI navigation

Entry point: `LoginActivity` → `MainActivity` (Navigation Drawer).
Six fragments: `HomeFragment`, `ScannerFragment`, `AttendanceFragment`, `SyncFragment`, `WorkersFragment`, `SettingsFragment`.

### Localization

All user-facing strings (errors, incident types, labels) are in **Spanish**.

### Scanner integration

`DataWedgeManager` listens for Honeywell DataWedge broadcast intents (default action `com.honeywell.sample.action.BARCODE`). Hardware scanning only works on compatible Honeywell devices (e.g., EDA52) with DataWedge configured to output scan intents.
