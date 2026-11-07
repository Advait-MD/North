"use strict";

const test = require("node:test");
const { before, after, beforeEach } = test;
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const {
  doc, setDoc, getDoc, getDocs, deleteDoc, updateDoc, collection, query, where, orderBy, startAt, endAt,
} = require("firebase/firestore");
const { ref, uploadBytes, getBytes, deleteObject } = require("firebase/storage");

const ROOT = path.join(__dirname, "..");
let env;

const place = (over = {}) => ({
  id: "p1",
  name: "Library",
  latitude: 28.6,
  longitude: 77.2,
  radius: 50,
  enabled: true,
  ownerId: "alice",
  visibility: "public",
  geohash: "ttnfv",
  createdAt: 1,
  ...over,
});
const fileMeta = (placeId, fileId, over = {}) => ({
  placeId,
  displayName: "notes.pdf",
  storagePath: `places/${placeId}/${fileId}`,
  mimeType: "application/pdf",
  sizeBytes: 1234,
  visibility: "public",
  ownerId: "alice",
  createdAt: 1,
  ...over,
});

const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();
const bucket = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).storage();

async function seed(fn) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore(), ctx.storage()));
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-north",
    firestore: { rules: fs.readFileSync(path.join(ROOT, "firestore.rules"), "utf8") },
    storage: { rules: fs.readFileSync(path.join(ROOT, "storage.rules"), "utf8") },
  });
});
after(async () => env && env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await seed(async (f, s) => {
    await setDoc(doc(f, "geofence_areas/p1"), place());
    await setDoc(doc(f, "geofence_areas/p1/entities/pub"), fileMeta("p1", "pub"));
    await setDoc(doc(f, "geofence_areas/p1/entities/priv"), fileMeta("p1", "priv", { visibility: "private" }));
    await setDoc(doc(f, "geofence_areas/secret"), place({ id: "secret", visibility: "private" }));
    await setDoc(doc(f, "geofence_areas/legacy"), { id: "legacy", name: "Old", latitude: 1, longitude: 1, radius: 20, enabled: true, ownerId: "alice" });
    await uploadBytes(ref(s, "places/p1/pub"), new Uint8Array([1, 2, 3]), { contentType: "application/pdf" });
  });
});

// ---------- places ----------

test("anyone signed in can read a public place; nobody signed out can", async () => {
  await assertSucceeds(getDoc(doc(db("bob"), "geofence_areas/p1")));
  await assertFails(getDoc(doc(db(null), "geofence_areas/p1")));
});

test("private and legacy places are owner-only", async () => {
  await assertFails(getDoc(doc(db("bob"), "geofence_areas/secret")));
  await assertFails(getDoc(doc(db("bob"), "geofence_areas/legacy")));
  await assertSucceeds(getDoc(doc(db("alice"), "geofence_areas/secret")));
  await assertSucceeds(getDoc(doc(db("alice"), "geofence_areas/legacy")));
});

test("the app's discovery and 'my places' queries are allowed", async () => {
  const places = collection(db("bob"), "geofence_areas");
  await assertSucceeds(getDocs(query(places, where("visibility", "==", "public"),
    orderBy("geohash"), startAt("tt"), endAt("tt~"))));
  await assertSucceeds(getDocs(query(places, where("ownerId", "==", "bob"))));
  // An unfiltered list would expose private places, so it must be refused.
  await assertFails(getDocs(places));
});

test("users can create places only as themselves and with valid fields", async () => {
  const bob = db("bob");
  await assertSucceeds(setDoc(doc(bob, "geofence_areas/b1"), place({ id: "b1", ownerId: "bob" })));
  await assertFails(setDoc(doc(bob, "geofence_areas/b2"), place({ id: "b2", ownerId: "alice" })));
  await assertFails(setDoc(doc(bob, "geofence_areas/b3"), place({ id: "b3", ownerId: "bob", radius: 5000 })));
  await assertFails(setDoc(doc(bob, "geofence_areas/b4"), place({ id: "b4", ownerId: "bob", visibility: "everyone" })));
  await assertFails(setDoc(doc(bob, "geofence_areas/b5"), place({ id: "b5", ownerId: "bob", admin: true })));
});

test("only the owner can update or delete a place, and ownership can't move", async () => {
  await assertFails(updateDoc(doc(db("bob"), "geofence_areas/p1"), { name: "Mine now" }));
  await assertFails(deleteDoc(doc(db("bob"), "geofence_areas/p1")));
  await assertFails(updateDoc(doc(db("alice"), "geofence_areas/p1"), { ownerId: "bob" }));
  await assertSucceeds(updateDoc(doc(db("alice"), "geofence_areas/p1"), { name: "Central Library" }));
  await assertSucceeds(deleteDoc(doc(db("alice"), "geofence_areas/p1")));
});

// ---------- file metadata ----------

test("visitors see public file metadata only, through a filtered query", async () => {
  const files = collection(db("bob"), "geofence_areas/p1/entities");
  await assertSucceeds(getDocs(query(files, where("visibility", "==", "public"))));
  await assertSucceeds(getDoc(doc(db("bob"), "geofence_areas/p1/entities/pub")));
  await assertFails(getDoc(doc(db("bob"), "geofence_areas/p1/entities/priv")));
  await assertFails(getDocs(files));
});

test("the owner lists every file", async () => {
  await assertSucceeds(getDocs(collection(db("alice"), "geofence_areas/p1/entities")));
});

test("only the place owner can publish, with a matching storage path", async () => {
  await assertSucceeds(setDoc(doc(db("alice"), "geofence_areas/p1/entities/n1"), fileMeta("p1", "n1")));
  await assertFails(setDoc(doc(db("bob"), "geofence_areas/p1/entities/n2"), fileMeta("p1", "n2", { ownerId: "bob" })));
  await assertFails(setDoc(doc(db("alice"), "geofence_areas/p1/entities/n3"),
    fileMeta("p1", "n3", { storagePath: "places/other/x" })));
});

test("a private place can't hold public files", async () => {
  await assertFails(setDoc(doc(db("alice"), "geofence_areas/secret/entities/f"), fileMeta("secret", "f")));
  await assertSucceeds(setDoc(doc(db("alice"), "geofence_areas/secret/entities/f"),
    fileMeta("secret", "f", { visibility: "private" })));
});

test("the old geofences/* collection is closed", async () => {
  await assertFails(getDocs(collection(db("alice"), "geofences/p1/entities")));
  await assertFails(setDoc(doc(db("alice"), "geofences/p1/entities/x"), { localUri: "content://x" }));
});

// ---------- storage ----------

test("visitors can't read bytes directly, even for public files", async () => {
  await assertFails(getBytes(ref(bucket("bob"), "places/p1/pub")));
  await assertSucceeds(getBytes(ref(bucket("alice"), "places/p1/pub")));
});

test("only the place owner uploads, within size and type limits", async () => {
  const pdf = { contentType: "application/pdf" };
  await assertSucceeds(uploadBytes(ref(bucket("alice"), "places/p1/new"), new Uint8Array([1]), pdf));
  await assertFails(uploadBytes(ref(bucket("bob"), "places/p1/evil"), new Uint8Array([1]), pdf));
  await assertFails(uploadBytes(ref(bucket("alice"), "places/p1/app"), new Uint8Array([1]),
    { contentType: "application/vnd.android.package-archive" }));
  await assertFails(uploadBytes(ref(bucket("alice"), "places/p1/big"),
    new Uint8Array(50 * 1024 * 1024 + 1), pdf));
  await assertFails(uploadBytes(ref(bucket("alice"), "elsewhere/x"), new Uint8Array([1]), pdf));
});

test("only the place owner deletes bytes", async () => {
  await assertFails(deleteObject(ref(bucket("bob"), "places/p1/pub")));
  await assertSucceeds(deleteObject(ref(bucket("alice"), "places/p1/pub")));
});
