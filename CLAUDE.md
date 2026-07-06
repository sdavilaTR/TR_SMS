# CLAUDE.md

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

> **Note**: `gradle.properties` points to custom JBR at `C:/Users/sdavila.AD/Documents/HassiSiteApp/custom_jbr`, includes SSL truststore workarounds. Build fail on new machine: update `org.gradle.java.home` or remove to use system Java 17.

## Architecture

**MVVM + Repository + Service Locator** — no Dagger/Hilt. Singletons held by `ServiceLocator.kt` (`object`), reads `AtlasApp.instance.database` on first access.

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
| `ServiceLocator.kt` | Central DI: lazy-creates singletons; exposes all DAOs |
| `AtlasApp.kt` | App class; init Room, stores global `instance` ref |
| `ProfileManager.kt` | 5 profiles: USER, HSE, ADMIN, PRE, DEV — controls API URL + menu |
| `ApiClient.kt` | Retrofit wrapper; resolves primary/fallback URL via `/health`; injects JWT + Device-ID via OkHttp interceptor |
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

PRE/DEV switch resets local DB. Unlock non-USER profiles: `ATLAS2026`. PRO via public reverse proxy prepending `/api` — `ApiClient` handles transparently.

### Database (Room — v8, 33 tables)

**Original tables:** `config`, `projects`, `zones`, `contractors`, `persons`, `access_points`, `crypto_keys`, `revoked_tokens`, `access_logs`, `incidents`, `work_sessions`, `pending_photos`, `hse_observations`, `hse_observation_photos`, `vehicles`, `training_compliance`, `document_compliance`.

**SMS tables (v7→v8):** `sms_spool`, `sms_packing_list`, `sms_packing_list_spool`, `sms_vehicle`, `sms_area`, `sms_spec`, `sms_subcontractor`, `sms_spool_event`, `sms_spool_property`, `sms_spool_status`, `sms_spool_status_flags`, `sms_bore_size`, `sms_incomplete_status`, `sms_iso_type`, `sms_position`, `sms_unit`.

Unsynced records: `synced = false`; `SyncService` uploads + flips flag. New tables: add `MIGRATION_N_N+1` in `AtlasDatabase.kt`, bump `version`.

### SMS Module

SMS (Spool Management System) syncs spools, packing lists, vehicles per project. Data: API → raw JSON → `parseSpoolEntities()` / `parsePackingListEntities()` / `parseVehicleEntities()` → Room. Parse helpers duplicated across `MainActivity`, `HomeFragment`, `SyncFragment` — prefer `MainActivity.syncSmsData()`, don't add 4th copy.

SMS endpoints use `projectCode` (string) not `projectId` (int). Selected project: `config` key `"selected_project_id"` (default `6`).

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

Profile visibility enforced in `MainActivity.applyProfileMenuVisibility()`, re-applied via `refreshProfileMenu()` after changes.

### Auto-sync

`MainActivity` runs coroutine loop every 60 s (`AUTO_SYNC_INTERVAL_MS`): `SyncService.fullSync()` then `syncSmsData()`. Tied to `onResume`/`onPause`; stops when backgrounded. `DataWedgeManager` registered/unregistered on resume/pause.

### Localization

All strings in **Spanish** (`res/values/strings.xml`).

### Scanner integration

`DataWedgeManager` listens for Honeywell DataWedge broadcast intents (default action `com.honeywell.sample.action.BARCODE`). Hardware scan only on Honeywell devices (e.g., EDA52) with DataWedge configured for scan intents.
