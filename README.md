# North

> Location-gated file shortcuts for Android.

North is an Android app that ties files on your phone to places. You draw a
circular **geofence** around where you're standing, attach images, videos or
PDFs to it, and those files can only be opened from the app while you're
inside that area. When you enter or leave a geofence, North posts a
notification and swaps your home and lock-screen wallpaper.

Built with Kotlin, Google Play Services geofencing, and Firebase (Auth + Firestore).

---

## How it works

1. **Sign in** with email and password (Firebase Auth).
2. **Create a geofence**: pick a radius, tap *Create Geofence*, and name it.
   North takes one GPS fix, saves the fence to Firestore, and registers it with Play Services.
3. **Attach files**: open a fence and tap **+**. Only the file's name, type and on-device
   `content://` URI go to Firestore. **The file itself never leaves the phone.**
4. **Walk in or out**: Play Services wakes `GeofenceBroadcastReceiver`, which records which
   fences you're inside, then updates the wallpaper and posts a notification.
5. **Open files**: tap a file. It opens only while you're inside its fence.
   Long-press a file to detach it.

```text
 MainActivity ──create/delete──▶ GeofenceRepository ──▶ Firestore
      │                                 ▲
      └──register──▶ GeofenceRegistrar ─┼──▶ Play Services Geofencing
                                        │            │ ENTER / EXIT
 GeofenceDetailActivity ◀── live ──┐    │            ▼
   (opens files if inside)         │    │  GeofenceBroadcastReceiver
                                   │    │    ├─ GeofenceStateStore (which fences you're in)
                                   └────┼────┤
                                        │    ├─ Wallpaper
                                        └────┴─ Notifications (fence name from Firestore)
```

## Code map

All code lives in `app/src/main/java/com/nimbus/spatial/`, in three packages.

### `ui/` — screens

| File | What it does |
|---|---|
| `LoginActivity.kt` | Sign in / create account |
| `MainActivity.kt` | Home screen: create, list and delete geofences; log out |
| `GeofenceDetailActivity.kt` | One fence's files: attach, open (only when inside), remove |
| `LocationPermissions.kt` | The two-step location permission flow |
| `GeofenceAdapter.kt`, `AttachedFileAdapter.kt` | The two lists |
| `Toasts.kt` | `context.toast("...")` helper |

### `geofence/` — background work

| File | What it does |
|---|---|
| `GeofenceRegistrar.kt` | Registers and removes fences with Play Services |
| `GeofenceBroadcastReceiver.kt` | Handles ENTER/EXIT events |
| `GeofenceBootReceiver.kt` | Re-registers fences after a reboot or app update |
| `GeofenceState.kt` | Pure logic: which fences you're inside, and what changed (unit-tested) |
| `GeofenceStateStore.kt` | Saves that state so it survives the app being killed |
| `LocationService.kt` | Low-power location request that makes fence events arrive sooner |
| `Notifications.kt`, `Wallpaper.kt` | What happens when you cross a fence |

### `data/` — Firestore

| File | What it does |
|---|---|
| `GeofenceArea.kt`, `AttachedFile.kt` | The two data types |
| `GeofenceRepository.kt` | Every Firestore read and write, always scoped to the signed-in user |

Firestore layout (secured by `firestore.rules`, where each user only sees their own documents):

```text
geofence_areas/{areaId}                 id, name, latitude, longitude, radius, ownerId
geofences/{areaId}/entities/{fileId}    fileName, localUri, mimeType, timestamp, ownerId
```

## Where to change things

| To change... | Look in |
|---|---|
| The radius range (10–200 m) | `slider_radius` in `res/layout/activity_main.xml` |
| What happens on enter/exit | `GeofenceBroadcastReceiver.handleTransition()` |
| Notification text or channels | `geofence/Notifications.kt` |
| The wallpapers | `res/drawable/in_fence.png`, `out_fence.png` |
| Which file types can be attached | `pickFile.launch(...)` in `GeofenceDetailActivity` |
| What data is stored | `data/GeofenceRepository.kt` (and update `firestore.rules`) |
| Battery vs. responsiveness | the location request in `geofence/LocationService.kt` |

## Honest limitations

- **The "lock" is a UI gate, not encryption.** Files stay unencrypted on the phone and can be
  opened with any gallery or file manager. GPS can be spoofed with mock-location apps.
- Attached files are on-device URIs, so they don't sync between phones.
- Background geofence events need **"Allow all the time"** location access, and Play Services
  can deliver them a few minutes late.
- North overwrites your wallpaper; it doesn't restore the previous one.

## Setup

1. Open the project in Android Studio (AGP 8.10, Kotlin 2.0, `minSdk` 26, `targetSdk` 35).
2. Create a Firebase project, enable **Email/Password** auth and **Cloud Firestore**, and replace
   `app/google-services.json` with your own.
3. Deploy the security rules:

   ```bash
   firebase deploy --only firestore:rules
   ```

4. Run on a device with Google Play Services. Grant location, then choose
   **"Allow all the time"** when asked.

## Tests

Unit tests (the inside/outside state logic):

```bash
./gradlew testDebugUnitTest
```

Firestore security rules, against the local emulator (needs Node and Java):

```bash
cd firestore-tests && npm install && npm test
```
