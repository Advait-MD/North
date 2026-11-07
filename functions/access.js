"use strict";

/**
 * Pure access-control logic for getFileAccessUrl, kept separate so it can be unit-tested
 * without Firebase. Mirrors app/src/main/java/com/nimbus/spatial/GeoMath.kt.
 */

const EARTH_RADIUS_M = 6371008.8;

/** GPS error we forgive, capped so a vague fix can't unlock a place far away. */
const MAX_ACCURACY_SLACK_M = 50;

/** Fixes worse than this are rejected outright. */
const MAX_ACCEPTED_ACCURACY_M = 200;

function toRad(deg) {
  return (deg * Math.PI) / 180;
}

function distanceMeters(lat1, lng1, lat2, lng2) {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

function isFiniteNumber(v) {
  return typeof v === "number" && Number.isFinite(v);
}

/**
 * Decides whether `uid` may download `file` from `place` while standing at `location`.
 *
 * @returns {{allowed: true} | {allowed: false, code: string, message: string}}
 */
function evaluateAccess({ uid, place, file, location }) {
  if (!uid) {
    return { allowed: false, code: "unauthenticated", message: "Sign in first." };
  }
  if (!place || !file) {
    return { allowed: false, code: "not-found", message: "File not found." };
  }
  if (place.ownerId === uid) {
    return { allowed: true };
  }
  if (place.visibility !== "public" || file.visibility !== "public") {
    // Same answer as a missing file, so private files can't be probed for.
    return { allowed: false, code: "not-found", message: "File not found." };
  }
  if (place.enabled === false) {
    return { allowed: false, code: "permission-denied", message: "This place is switched off." };
  }
  if (!location || !isFiniteNumber(location.latitude) || !isFiniteNumber(location.longitude)) {
    return { allowed: false, code: "invalid-argument", message: "Your location is required." };
  }
  if (Math.abs(location.latitude) > 90 || Math.abs(location.longitude) > 180) {
    return { allowed: false, code: "invalid-argument", message: "Invalid location." };
  }
  const accuracy = isFiniteNumber(location.accuracy) ? Math.max(0, location.accuracy) : 0;
  if (accuracy > MAX_ACCEPTED_ACCURACY_M) {
    return {
      allowed: false,
      code: "failed-precondition",
      message: "Your location is too imprecise. Try again outdoors.",
    };
  }
  const distance = distanceMeters(
    location.latitude, location.longitude, place.latitude, place.longitude
  );
  const limit = Number(place.radius) + Math.min(accuracy, MAX_ACCURACY_SLACK_M);
  if (!(distance <= limit)) {
    return {
      allowed: false,
      code: "permission-denied",
      message: `You're ${Math.round(distance - Number(place.radius))} m outside this place.`,
    };
  }
  return { allowed: true };
}

module.exports = {
  distanceMeters,
  evaluateAccess,
  MAX_ACCURACY_SLACK_M,
  MAX_ACCEPTED_ACCURACY_M,
};
