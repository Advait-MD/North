"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { distanceMeters, evaluateAccess } = require("../access");

// India Gate, New Delhi.
const CENTER = { latitude: 28.6129, longitude: 77.2295 };
const place = (over = {}) => ({
  ownerId: "owner",
  visibility: "public",
  enabled: true,
  radius: 50,
  ...CENTER,
  ...over,
});
const file = (over = {}) => ({ visibility: "public", ...over });
// Roughly `m` metres north of CENTER.
const north = (m) => ({ latitude: CENTER.latitude + m / 111195, longitude: CENTER.longitude });

test("distanceMeters is ~111 km per degree of latitude", () => {
  const d = distanceMeters(0, 0, 1, 0);
  assert.ok(Math.abs(d - 111195) < 10, `got ${d}`);
  assert.equal(distanceMeters(10, 20, 10, 20), 0);
});

test("owner can open from anywhere, even private files", () => {
  const r = evaluateAccess({
    uid: "owner",
    place: place({ visibility: "private" }),
    file: file({ visibility: "private" }),
    location: null,
  });
  assert.deepEqual(r, { allowed: true });
});

test("visitor inside a public place can open a public file", () => {
  const r = evaluateAccess({ uid: "v", place: place(), file: file(), location: { ...north(30), accuracy: 10 } });
  assert.equal(r.allowed, true);
});

test("visitor outside the radius is denied", () => {
  const r = evaluateAccess({ uid: "v", place: place(), file: file(), location: { ...north(200), accuracy: 5 } });
  assert.equal(r.allowed, false);
  assert.equal(r.code, "permission-denied");
});

test("accuracy slack is capped so a vague fix can't unlock far places", () => {
  // 50 m radius + at most 50 m slack: 85 m passes with 40 m accuracy; 120 m fails even claiming 100 m accuracy.
  assert.equal(evaluateAccess({ uid: "v", place: place(), file: file(), location: { ...north(85), accuracy: 40 } }).allowed, true);
  assert.equal(evaluateAccess({ uid: "v", place: place(), file: file(), location: { ...north(120), accuracy: 100 } }).allowed, false);
});

test("very imprecise fixes are rejected", () => {
  const r = evaluateAccess({ uid: "v", place: place(), file: file(), location: { ...north(0), accuracy: 500 } });
  assert.equal(r.code, "failed-precondition");
});

test("private files and private places look like missing files to visitors", () => {
  for (const [p, f] of [[place(), file({ visibility: "private" })], [place({ visibility: "private" }), file()]]) {
    const r = evaluateAccess({ uid: "v", place: p, file: f, location: north(0) });
    assert.equal(r.code, "not-found");
  }
});

test("missing location, bad input and signed-out callers are rejected", () => {
  assert.equal(evaluateAccess({ uid: "v", place: place(), file: file(), location: {} }).code, "invalid-argument");
  assert.equal(evaluateAccess({ uid: "v", place: place(), file: file(), location: { latitude: "28", longitude: 77 } }).code, "invalid-argument");
  assert.equal(evaluateAccess({ uid: "v", place: place(), file: file(), location: { latitude: 91, longitude: 0 } }).code, "invalid-argument");
  assert.equal(evaluateAccess({ uid: null, place: place(), file: file(), location: north(0) }).code, "unauthenticated");
  assert.equal(evaluateAccess({ uid: "v", place: null, file: file(), location: north(0) }).code, "not-found");
});

test("disabled places are locked for visitors", () => {
  const r = evaluateAccess({ uid: "v", place: place({ enabled: false }), file: file(), location: north(0) });
  assert.equal(r.code, "permission-denied");
});
