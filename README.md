# North

> Location-gated file shortcuts for Android.

North is an Android app that ties files on your phone to places. You draw a
circular **geofence** around where you're standing, attach images, videos or
PDFs to it, and those files can only be opened from the app while you're
inside that area. When you enter or leave a geofence, North posts a
notification and swaps your home and lock-screen wallpaper.

It's a personal project built with Kotlin, Google Play Services geofencing
and Firebase (Auth and Firestore).

---

## How it works

```text
 ┌──────────────┐   create fence    ┌──────────────────────────┐
 │ MainActivity │ ────────────────▶ │ Firestore: geofence_areas│
 │  (GPS fix +  │                   │  {name, lat, lng, radius,│
 │   radius)    │ ── register ──┐   │   isEnabled, ownerId}    │
 └──────┬───────┘               │   └──────────────────────────┘
        │ open fence            ▼
        │              ┌──────────────────┐  ENTER / EXIT  ┌────────────────────────────┐
        │              │ Play Services     │ ─────────────▶ │ GeofenceBroadcastReceiver  │
        │              │ Geofencing API    │                │  • updates inside-state    │
        │              └──────────────────┘                │  • notification + wallpaper│
        ▼                                                   └─────────────┬──────────────┘
 ┌───────────────────────┐   reads inside-state                           │
 │ GeofenceDetailActivity│ ◀────────── GeofenceStateStore (SharedPrefs) ◀─┘
 │  lists attached files │
 │  locked when outside  │ ──▶ Firestore: geofences/{id}/entities
 └───────────────────────┘      {fileName, localUri, mimeType, ownerId}
```

1. **Sign in** with email and password (Firebase Auth).
2. **Create a geofence**: wait for a GPS fix, pick a radius (slider) and a name.
   The fence is saved to Firestore and registered with the Geofencing API.
3. **Attach files**: open a fence and pick a file. Only its metadata and
   on-device `content://` URI are stored in Firestore. **The file itself
   never leaves the phone.**
4. **Enter/exit**: the broadcast receiver records which fences you're inside
   (persisted in `SharedPreferences`), then notifies you and changes the wallpaper.
5. **Open files**: files in a fence are clickable only while you're inside it.

## Honest limitations

- **The "lock" is a UI gate, not encryption.** Files stay unencrypted on the
  device and can be opened with any gallery or file manager. GPS can also be
  spoofed with mock-location apps.
- Attached files are device-local URIs, so they don't sync between devices.
  Deleting a geofence removes its file records, not the files themselves.
- Geofence events in the background need **"Allow all the time"** location access,
  and Play Services can deliver them a few minutes late.

## Project layout

| File | Responsibility |
|---|---|
| `LoginActivity.kt` | Email/password sign-in and sign-up (separate actions) |
| `MainActivity.kt` | Permissions, creating, listing and deleting geofences |
| `GeofenceDetailActivity.kt` | Attaching and opening files for one geofence; locks/unlocks live |
| `GeofenceRepository.kt` | All Firestore reads/writes, always scoped to the signed-in user |
| `GeofenceRegistrar.kt` | Registering/removing fences with the Play Services Geofencing API |
| `GeofenceBroadcastReceiver.kt` | ENTER/EXIT transitions: state, wallpaper, notification |
| `GeofenceBootReceiver.kt` | Re-registers fences after reboot or app update |
| `GeofenceState.kt` / `GeofenceStateStore.kt` | Which fences the user is inside (pure logic + persistence) |
| `LocationService.kt` | Low-power foreground location request that keeps geofencing responsive |
| `WallpaperUtils.kt` | Wallpaper switching |
| `firestore.rules` | Firestore security rules (per-user ownership) |

## Setup

1. Open the project in Android Studio (AGP 8.10, Kotlin 2.0, `minSdk` 26, `targetSdk` 35).
2. Create a Firebase project, enable **Email/Password** auth and **Cloud Firestore**,
   and replace `app/google-services.json` with your own.
3. Deploy the security rules:

   ```bash
   firebase deploy --only firestore:rules
   ```

4. Run on a device with Google Play Services. Grant location, then choose
   **"Allow all the time"** when prompted.

## Tests

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the geofence inside/outside state logic. The Firestore security
rules have their own emulator tests (needs Node and Java):

```bash
cd firestore-tests && npm install && npm test
```
