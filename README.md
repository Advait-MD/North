# North

> Pin photos, videos and PDFs to real places. Anyone standing there can open the ones you make public.

North is an Android app for location-locked file sharing. You stand somewhere, draw a circle
around it (a *place*), and upload files to it. Your private files are yours to open from
anywhere. Your public files can be opened by any North user who is physically inside the circle,
and only while they're there.

Ideas it fits: lecture notes that unlock in the lecture hall, a photo wall for an event venue,
a museum exhibit's extra material, a treasure hunt.

---

## How it works

```text
 Publisher (place owner)                          Visitor
 ───────────────────────                          ───────
 1. Create place here  ──► Firestore              1. Walks around; LocationService finds
    (centre, radius,       geofence_areas/{id}       public places within 5 km (geohash query)
     public|private,                              2. Android geofencing watches the nearest 100
     geohash)                                     3. Enters a circle → notification, files unlock
 2. Upload file ──► Storage places/{id}/{fileId}  4. Taps a file ─► getFileAccessUrl(placeId,
              └──► Firestore …/entities/{fileId}     fileId, lat, lng, accuracy)
                   (name, size, type, public|private)                │
                                                   Cloud Function checks owner OR
                                                   public + within radius, then returns
                                                   a signed URL valid for 5 minutes ◄─┘
```

- **Files are actually uploaded.** The bytes go to Firebase Storage; Firestore keeps the metadata.
- **Visibility is per place and per file.** A private place's files are always private.
- **The server decides access, not the phone.** Visitors can't read Storage directly
  (`storage.rules`); the only way in is the `getFileAccessUrl` function, which checks the
  caller's location against the place before signing a short-lived URL.
- **Discovery scales past Android's 100-geofence limit.** Places carry a geohash; the app
  queries public places near you and registers only the nearest 100, then re-queries after
  you move 1 km or 15 minutes pass.
- **"Am I inside?" survives process death.** It's kept in SharedPreferences
  (`GeofenceStateStore`) and re-checked against your last location when you open a place.

## Honest limitations

- **Location can be spoofed.** Mock-location apps can fake GPS. The server check makes this
  harder than flipping a flag in the app, but it does not make it impossible. Don't put
  anything at a public place that would be a problem if someone got it without being there.
- **Public uploads need moderation.** There's no reporting or takedown flow yet. You need one
  before opening this up to strangers. Platforms that host user content carry legal
  obligations; in India, for example, the IT Rules 2021.
- **Geofences are coarse.** Android recommends a radius of at least 100 m for reliable
  geofence alerts; smaller circles can miss enter/exit events. File opening still works,
  because it checks a fresh GPS fix on the server.
- Only the owner of a place can upload to it.

---

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/com/nimbus/spatial/MainActivity.kt` | Your places + public places nearby; create/delete places |
| `.../GeofenceDetailActivity.kt` | Files at a place: upload (owner), open, delete |
| `.../PlaceRepository.kt` | Firestore/Storage reads and writes, upload, cascade delete, access-URL call |
| `.../PlaceSync.kt` | Geohash discovery and registering the nearest 100 geofences |
| `.../GeofenceBroadcastReceiver.kt` | Enter/exit events → notification, wallpaper, inside-state |
| `.../GeofenceStateStore.kt` | Durable inside / registered / last-sync state |
| `.../LocationService.kt` | Foreground service that keeps nearby places fresh |
| `.../GeoMath.kt` | Distance, inside-check, nearest-N (pure Kotlin, unit-tested) |
| `functions/` | `getFileAccessUrl` Cloud Function and its tests |
| `firestore.rules`, `storage.rules`, `firestore.indexes.json` | Security rules and the discovery index |
| `rules-tests/` | Emulator tests for both rule sets |

### Data model

```text
geofence_areas/{placeId}
  name, latitude, longitude, radius (10–1000 m), enabled,
  ownerId, visibility ("public" | "private"), geohash, createdAt
geofence_areas/{placeId}/entities/{fileId}
  placeId, displayName, storagePath ("places/{placeId}/{fileId}"),
  mimeType, sizeBytes, visibility, ownerId, createdAt
Storage: places/{placeId}/{fileId}      (images, videos, PDFs; < 50 MB)
```

---

## Setup

1. Create a Firebase project with **Authentication (email/password)**, **Firestore**,
   **Storage** and **Functions** (Functions needs the Blaze plan).
2. Put your `google-services.json` in `app/`.
3. Let the Functions service account sign URLs: in Google Cloud IAM, give the account your
   functions run as (by default `PROJECT_NUMBER-compute@developer.gserviceaccount.com`) the
   **Service Account Token Creator** role on itself.
4. Deploy the backend:

   ```bash
   npm install -g firebase-tools
   ```

   ```bash
   (cd functions && npm install)
   ```

   ```bash
   firebase deploy --only firestore:rules,firestore:indexes,storage,functions
   ```

5. Open the project in Android Studio and run the `app` configuration.

### Tests

```bash
(cd functions && npm test)
```

```bash
(cd rules-tests && npm install && npm test)
```

```bash
./gradlew :app:testDebugUnitTest
```

### Upgrading from older builds

Earlier builds saved only a device-local `content://` URI for each file. Those files were never
uploaded and can't be recovered from the server. The old `geofences/{id}/entities` collection is
now closed by the rules, and the app ignores it. Places created before visibility existed count
as private until their owner recreates them.

## License

MIT. See [LICENSE](LICENSE).
