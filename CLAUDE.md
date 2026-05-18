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

# Run a single test class
./gradlew test --tests "com.example.hassiwrapper.YourTestClass"

# Clean build artifacts
./gradlew clean
```

> **Note**: `gradle.properties` points to a custom JBR at `C:/Users/sdavila.AD/Documents/HassiSiteApp/custom_jbr` and includes SSL truststore workarounds. If builds fail on a new machine, update `org.gradle.java.home` or remove it to use system Java 17.

## Architecture

**MVVM + Repository + Service Locator** — no Dagger/Hilt. All singletons are constructed and held by `ServiceLocator.kt` (an `object`), which reads `AtlasApp.instance.database` on first access.

### Dependency flow

```
UI (Fragment / Activity)
  → ServiceLocator           (provides lazy singletons + DAO accessors)
  → Services                 (ClockingService, SyncService, IncidentService, ObservationService, HeartbeatManager)
  → Repositories             (AuthRepository, ConfigRepository)
  → DAOs + ApiClient
  → Room DB  /  Retrofit
```

### Key files

| File | Role |
|------|------|
| `ServiceLocator.kt` | Central DI: lazy-creates every singleton; also exposes every DAO directly |
| `AtlasApp.kt` | Application class; initialises Room, stores global `instance` ref |
| `ProfileManager.kt` | 5 profiles: USER, HSE, ADMIN, PRE, DEV — controls API URL and menu visibility |
| `ApiClient.kt` | Retrofit wrapper; resolves primary vs. fallback URL via `/health` ping; injects JWT + Device-ID via OkHttp interceptor |
| `AuthRepository.kt` | JWT login, storage, expiry; `reLoginWithStoredCode()` for session recovery |
| `ClockingService.kt` | Core access-control logic — anti-bounce (30 s), ENTRY/EXIT/AUTO resolution, session management |
| `SyncService.kt` | Full sync orchestration: device registration → download master data → upload logs/incidents/sessions/observations |
| `HeartbeatManager.kt` | Periodic background API ping |
| `DataWedgeManager.kt` | Honeywell DataWedge barcode scanner via broadcast intents; emits via Kotlin `Flow` |
| `UpdateChecker.kt` / `UpdateInstaller.kt` | OTA APK update check and download/install |
| `data/db/entities/Entities.kt` | All original 17 Room entities |
| `data/db/entities/SmsEntities.kt` | 16 SMS-module Room entities (`sms_*` tables) |
| `data/db/dao/Daos.kt` | All original DAOs |
| `data/db/dao/SmsDaos.kt` | SMS-module DAOs |
| `network/dto/Dtos.kt` | All original Retrofit DTOs |
| `network/dto/SpoolDto.kt` / `SmsPackingListDto.kt` / `SmsVehicleDto.kt` | SMS-module DTOs |

### Profiles (`ProfileManager`)

| Profile | API URL | Menu |
|---------|---------|------|
| USER | PRO (`atlas.tecnicasreunidas.es` via reverse proxy) | Home, Scanner, Sync, Settings |
| HSE | PRO | + Passport, PackingLists, Observations, Inspections |
| ADMIN | PRO | All items |
| PRE | PRE (`web-atlas-api-pre.azurewebsites.net`) | All items |
| DEV | DEV (`web-atlas-api-dev.azurewebsites.net`) | All items |

Switching to PRE/DEV resets the local database. Access code to unlock non-USER profiles: `ATLAS2026`. PRO traffic routes through a public reverse proxy that prepends `/api` to every path — `ApiClient` handles this transparently.

### Database (Room — v8, 33 tables)

**Original tables:** `config`, `projects`, `zones`, `contractors`, `persons`, `access_points`, `crypto_keys`, `revoked_tokens`, `access_logs`, `incidents`, `work_sessions`, `pending_photos`, `hse_observations`, `hse_observation_photos`, `vehicles`, `training_compliance`, `document_compliance`.

**SMS tables (v7→v8):** `sms_spool`, `sms_packing_list`, `sms_packing_list_spool`, `sms_vehicle`, `sms_area`, `sms_spec`, `sms_subcontractor`, `sms_spool_event`, `sms_spool_property`, `sms_spool_status`, `sms_spool_status_flags`, `sms_bore_size`, `sms_incomplete_status`, `sms_iso_type`, `sms_position`, `sms_unit`.

Unsynced records use `synced = false`; `SyncService` uploads and flips the flag. When adding new tables, add a `MIGRATION_N_N+1` in `AtlasDatabase.kt` and bump `version`.

### SMS Module

The SMS (Spool Management System) module syncs spools, packing lists, and vehicles per project. Data flows: API → raw JSON → `parseSpoolEntities()` / `parsePackingListEntities()` / `parseVehicleEntities()` → Room. These three parse helpers are currently duplicated across `MainActivity`, `HomeFragment`, and `SyncFragment` — prefer `MainActivity.syncSmsData()` as the canonical path and avoid adding a fourth copy.

SMS endpoints use `projectCode` (string) not `projectId` (int). The selected project is stored in `config` table under key `"selected_project_id"` (defaults to `6`).

### API endpoints (`AtlasApiService.kt`)

**Auth / Sync:**
- `POST /v1/Auth/login`
- `GET  /api/trac/sync/download`
- `POST /api/trac/sync/upload` — access logs
- `POST /api/trac/sync/upload-incidents`
- `POST /api/trac/sync/upload-sessions`
- `POST /api/trac/sync/upload-observations`
- `POST /api/trac/sync/upload-observation-photos` (multipart)
- `POST /api/trac/sync/register-device`
- `GET  /api/trac/sync/compliance-bulk`
- `POST /api/trac/sync/heartbeat`
- `GET  /health`

**SMS (per `projectCode`):**
- `GET /api/atlas/projects/{code}/spools`
- `GET /api/atlas/projects/{code}/packing-lists`
- `GET /api/atlas/projects/{code}/packing-lists/{id}/spools`
- `GET /api/atlas/projects/{code}/vehicles`
- `GET /api/atlas/projects/{code}/areas`
- `GET /api/atlas/projects/{code}/specs`
- `GET /api/atlas/projects/{code}/subcontractors`
- `GET /api/atlas/projects/{code}/spools/{id}/events`
- `GET /api/atlas/projects/{code}/spools/{id}/property`
- `GET /api/atlas/projects/{code}/spools/{id}/status-flags`

**SMS globals:** `GET /api/atlas/sms/bore-sizes|iso-types|positions|spool-statuses|units|incomplete-statuses`

### UI navigation

Entry point: `LoginActivity` → `MainActivity` (Navigation Drawer).

Fragments: `HomeFragment`, `ScannerFragment`, `PassportFragment`, `PackingListsFragment`, `AttendanceFragment`, `SyncFragment`, `WorkersFragment`, `VehiclesFragment`, `ObservationFragment`, `ObservationsGeneralFragment`, `InspectionsFragment`, `SettingsFragment`.

Profile visibility is enforced in `MainActivity.applyProfileMenuVisibility()` and re-applied after profile changes via `refreshProfileMenu()`.

### Auto-sync

`MainActivity` runs a coroutine loop every 60 s (`AUTO_SYNC_INTERVAL_MS`) that calls `SyncService.fullSync()` then `syncSmsData()`. The loop is tied to `onResume`/`onPause` so it stops when the app is backgrounded. `DataWedgeManager` is also registered/unregistered on resume/pause.

### Localization

All user-facing strings are in **Spanish** (`res/values/strings.xml`).

### Scanner integration

`DataWedgeManager` listens for Honeywell DataWedge broadcast intents (default action `com.honeywell.sample.action.BARCODE`). Hardware scanning only works on compatible Honeywell devices (e.g., EDA52) with DataWedge configured to output scan intents.
