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
3. **Attach files**: open a fence and tap **+**. North encrypts a copy into its private storage,
   then offers to delete the original. Only the file's name and type go to Firestore.
   **The file itself never leaves the phone.**
4. **Walk in or out**: Play Services wakes `GeofenceBroadcastReceiver`, which records which
   fences you're inside, then updates the wallpaper and posts a notification.
5. **Open files**: tap a file. North checks that you're inside the fence (twice, see
   [Security model](#security-model)), decrypts a temporary copy, and opens it in a viewer app.
   Long-press a file to remove it.

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

All code lives in `app/src/main/java/com/nimbus/spatial/`, in four packages.

### `ui/` — screens

| File | What it does |
|---|---|
| `LoginActivity.kt` | Sign in / create account |
| `MainActivity.kt` | Home screen: create, list and delete geofences; log out |
| `GeofenceDetailActivity.kt` | One fence's files: attach, open (only when inside), remove |
| `GatedFileOpener.kt` | Opening a file: both security gates, then decrypt and view |
| `OriginalFileCleanup.kt` | Offers to delete the unencrypted original after attaching |
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

### `security/` — encryption and the location gate

| File | What it does |
|---|---|
| `FileVault.kt` | Encrypts/decrypts attached files (Jetpack Security `EncryptedFile`) and pins each fence's location |
| `LiveLocationGate.kt` | Takes a fresh location fix and rejects mock locations |
| `FenceCheck.kt` | Pure decision: inside, outside, mock or too imprecise (unit-tested) |
| `SafeFileName.kt` | Makes file names safe to use on disk (unit-tested) |

### `data/` — Firestore

| File | What it does |
|---|---|
| `GeofenceArea.kt`, `AttachedFile.kt` | The two data types |
| `GeofenceRepository.kt` | Every Firestore read and write, always scoped to the signed-in user |

Firestore layout (secured by `firestore.rules`, where each user only sees their own documents):

```text
geofence_areas/{areaId}                 id, name, latitude, longitude, radius, ownerId
geofences/{areaId}/entities/{fileId}    fileName, mimeType, timestamp, ownerId, encrypted
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
| How strict the live location check is | `MAX_ACCURACY_METERS` and `classify()` in `security/FenceCheck.kt` |
| The encryption library | only `security/FileVault.kt` uses it |

## Security model

**Threat model:** someone else gets hold of your unlocked phone, or knows your account password,
and wants to read your files while away from the place you tied them to.

### What protects your files

| Gate | Where | What it stops |
|---|---|---|
| **Encryption at rest** | `FileVault` | Files are stored as AES-256-GCM `EncryptedFile`s in app-private storage. The key lives in the Android Keystore (hardware-backed where available) and can't be exported, so copying the vault folder, or restoring it on another phone, yields nothing readable. |
| **Original removed** | `OriginalFileCleanup` | After encrypting, North offers to delete the original from the gallery or files. If you keep it, anyone can still open that copy. |
| **Gate 1: saved state** | `GatedFileOpener` | Files only open if geofence events say you're inside the fence. |
| **Gate 2: live location** | `LiveLocationGate`, `FenceCheck` | A fresh fix is taken on every open. Mock locations and fixes less precise than 100 m are rejected. |
| **Pinned fence location** | `FileVault.pinFence` | Gate 2 checks against where the fence was when its first file was attached, stored encrypted on the phone. Moving, enlarging or deleting-and-recreating the fence in Firestore doesn't move where files unlock. |
| **Firestore rules** | `firestore.rules` | Users only see their own data. Fences can be renamed but never moved, enlarged (max 1 km) or given away. File records can't be edited. |
| **Short-lived plaintext** | `FileVault.clearDecrypted` | A decrypted copy exists only while a viewer shows it. It's wiped when you come back to North, when you leave the fence, and on reboot or the next app start. It's shared only with that one viewer, via a non-exported `FileProvider`. |
| **Less leakage** | various | The file screen blocks screenshots and recent-apps previews (`FLAG_SECURE`). Lock-screen notifications hide place names. Backups and device transfers exclude North's data. |

### What it does *not* protect against

- **A rooted phone, or one with an unlocked bootloader.** Root can read app memory, hide mock
  locations from North, and use the Keystore key through North's own process. Nothing in an app
  can fully defend against that.
- **The viewer app.** Once a file is decrypted for viewing, the viewer can save or share it. A
  viewer that already has the file open can also keep showing it after North wipes the copy.
- **Originals you chose to keep**, and **files attached before encryption existed**. Those are
  marked "(not encrypted)" in the list; remove and re-attach them to protect them.
- **Someone standing inside the fence with your unlocked phone.** That's the intended way to get in.
- **File names and fence locations** are stored in Firestore unencrypted (only you can read them).

### Notes for maintainers

- `androidx.security:security-crypto` 1.1.0 is deprecated upstream and won't get new releases.
  It works, and everything that uses it is in `security/FileVault.kt`, so replacing it (for example
  with direct Android Keystore + AES-GCM) only touches that file. Keep the on-disk format readable
  or add a migration, or users lose their files.
- Encrypted files are only on the phone that attached them. Reinstalling the app, or clearing its
  data, destroys the key and therefore the files. Tell users before they delete originals.

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
