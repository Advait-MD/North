"use strict";

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { evaluateAccess } = require("./access");

initializeApp();

const URL_TTL_MS = 5 * 60 * 1000;

/**
 * Returns a short-lived signed download URL for a file pinned to a place.
 *
 * The app never reads other people's files from Storage directly (storage.rules forbid it);
 * this function is the only way in, and it checks ownership or physical presence first.
 *
 * Request: { placeId, fileId, latitude?, longitude?, accuracy? }
 * Response: { url, expiresAt }
 */
exports.getFileAccessUrl = onCall({ enforceAppCheck: false }, async (request) => {
  const uid = request.auth?.uid;
  const { placeId, fileId, latitude, longitude, accuracy } = request.data || {};

  if (typeof placeId !== "string" || typeof fileId !== "string" || !placeId || !fileId) {
    throw new HttpsError("invalid-argument", "placeId and fileId are required.");
  }

  const db = getFirestore();
  const placeRef = db.collection("geofence_areas").doc(placeId);
  const [placeSnap, fileSnap] = await Promise.all([
    placeRef.get(),
    placeRef.collection("entities").doc(fileId).get(),
  ]);
  const place = placeSnap.exists ? placeSnap.data() : null;
  const file = fileSnap.exists ? fileSnap.data() : null;

  const decision = evaluateAccess({
    uid,
    place,
    file,
    location: { latitude, longitude, accuracy },
  });
  if (!decision.allowed) {
    logger.info("Access denied", { uid, placeId, fileId, code: decision.code });
    throw new HttpsError(decision.code, decision.message);
  }

  const expectedPath = `places/${placeId}/${fileId}`;
  if (file.storagePath !== expectedPath) {
    // Never sign an arbitrary path taken from a document.
    logger.error("Unexpected storagePath", { placeId, fileId, storagePath: file.storagePath });
    throw new HttpsError("not-found", "File not found.");
  }

  const expiresAt = Date.now() + URL_TTL_MS;
  const [url] = await getStorage()
    .bucket()
    .file(expectedPath)
    .getSignedUrl({
      version: "v4",
      action: "read",
      expires: expiresAt,
      responseDisposition: `inline; filename="${encodeURIComponent(file.displayName || fileId)}"`,
    });

  logger.info("Access granted", { uid, placeId, fileId, owner: place.ownerId === uid });
  return { url, expiresAt };
});
