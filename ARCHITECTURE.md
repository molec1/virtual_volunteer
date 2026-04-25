# Virtual Volunteer — Architecture

Concise system overview for developers and AI assistants. **Not** a class-by-class catalog.

## Purpose

Offline-first Android app for **race timing using photos**: capture **start-line** crowds (participants pool), **finish-line** faces (match + timestamps), optional scans/names, exports (ZIP, CSV, protocol XML). Designed for field use without assuming a live network.

---

## Core entities (Room)

| Concept | Persistence | Role |
|--------|-------------|------|
| **Race** | `RaceEntity` | One event: ids, timestamps (created / started / finished), status, folders, last processed photo path, optional **list** menu preview path (`listThumbnailPath` → `race_list_thumb.jpg` next to `race.xml`). |
| **Participant** | `RaceParticipantHashEntity` | **One protocol person** per row: thumbnails, scan/name, identity-registry link, **`protocolFinishTimeEpochMillis`** / **`firstFinishSeenAtEpochMillis`**. Legacy columns **`embedding`** / **`embeddingFailed`** remain as a **synced mirror** of the primary stored vector (first row in `participant_embeddings`) for compatibility; the source of truth for face vectors is **`participant_embeddings`**. |
| **Participant embedding** | `ParticipantEmbeddingEntity` | **Many rows per participant**: comma-separated vector, **`EmbeddingSourceType`** (`START`, `FINISH_AUTO`, `FINISH_MANUAL_LINK`), optional **`sourcePhotoPath`**, **`qualityScore`**, timestamps. Matching uses **all** vectors for that participant (best cosine wins per participant). |
| **FinishDetection** | `FinishDetectionEntity` | One row per **matched** finish-face event: participant, race, **detectedAt**, source photo path, optional cosine score. Many rows per participant are allowed. |

**Official protocol finish time** lives on the participant row; detections are the raw timeline used to compute it (see aggregation below).

---

## Participant vs embeddings (multi-vector identity)

- **`RaceParticipantHashEntity`** = one finish line in the protocol / one dashboard card.
- **`ParticipantEmbeddingEntity`** = evidence vectors over time (start crop, successful finish matches, optional manual-merge provenance).
- **Cosine matching**: for each candidate finish embedding, compute similarity against **every** stored vector of each participant; that participant’s score is the **maximum** cosine among its vectors; the winning participant is the one with the highest score above threshold (same threshold as before).
- **Automatic finish match**: after **`recordFinishDetection`**, append the finish-face embedding with **`FINISH_AUTO`** when it is not already stored exactly (deduped by identical comma-separated string).
- **Manual merge via scan**: if two protocol rows end up with the **same trimmed scanned payload**, **`RaceRepository`** merges **donor → keeper** (the participant row whose scan UI was just updated): reassign **`finish_detections`** and **`participant_embeddings`** to the keeper, merge optional fields, delete the donor row, then recompute protocol finish for the keeper. This avoids parallel identities when the operator assigns the same bib/code to the “correct” participant.
- **Duplicate scan consolidation (registry + protocol)**: multiple **`identity_registry`** rows with the same trimmed scan code are merged (**smallest registry id** is keeper; participant rows pointing at donors are repointed; notes/thumbnail/embedding merged). Separately, within each race, protocol rows that share the **same effective scan** as the dashboard (`COALESCE` race `scannedPayload` with linked registry’s scan) are merged (**smallest participant id** keeper). **`consolidateScanMergesForRace`** / **`consolidateAllScanMerges`** run after barcode scan / face-lookup assign, on race detail **onResume**, and when opening the **Scanned on this device** list—so duplicate codes collapse without relying on a single code path.

---

## Start photo pipeline (`RacePhotoProcessor.ingestStartPhoto`)

1. Layout folders; decode image with **EXIF upright** orientation (`OrientedPhotoBitmap`).
2. **Detection** — ML Kit faces on the bitmap (separate from embedding quality).
3. Per face: expand/crop region, save **JPEG thumbnail**, run **TFLite embedder**.
4. **Participant row** inserted even when embedding fails (`embeddingFailed`, empty embedding string) so the person stays visible/debuggable.
5. **Duplicate skip** (multi-embedding): cosine match against **`listParticipantEmbeddingSets`** — skip insert if any existing participant’s **best** similarity to the new vector is ≥ threshold (same pool rule as finish matching).
6. On successful embedding, **`insertParticipantHash`** also inserts the first **`ParticipantEmbeddingEntity`** with **`START`**.
7. Optional **global identity registry** resolution after embedding succeeds.
8. Update race **last processed photo** path.

**Invariant:** detection (boxes) ≠ embedding/hash; hashing failure must not skip creating the participant row.

---

## Finish photo pipeline (`RacePhotoProcessor.processFinishPhotoInternal`)

1. Same decode + detect faces on oriented bitmap.
2. Per face: crop → embed → **match** against **`ParticipantEmbeddingSet`** list for **eligible pool** for **this photo only** (`availableThisPhoto`): cosine ≥ threshold using **best-over-embeddings** per participant; nearest-neighbour among participants not yet matched on **this** image (avoids double-assigning two faces to one identity in one frame).
3. **Cross-photo:** the same participant **can** match again on later finish photos (no global “already finished” exclusion).
4. Unmatched face above pool threshold but no slot left uses **create participant from finish** path (thumbnail + embedding + registry); first embedding row is **`FINISH_AUTO`**.
5. **Record finish detection** (`recordFinishDetection`) with cosine = **best** similarity vs the matched participant’s embedding set (or **1f** for a newly created finish-only participant’s first vector).
6. If the face **auto-matched** an **existing** participant, **append** the finish embedding (**`FINISH_AUTO`**) when not an exact duplicate string — **after** recording the detection.

Pipeline logs include **candidate photo path**, **nearest participant / best score**, **matched participant id**, **`embeddingAppended`**, and detection-record outcome.

---

## Finish aggregation (30-second first-series window)

Implemented in **`RaceProtocolFinishSync.recomputeProtocolFinishForParticipant`** (`FIRST_FINISH_SERIES_WINDOW_MS` is defined on **`RaceRepository`** and passed into that helper; same constant value `30_000`).

After **every** inserted `FinishDetection` for that participant:

- \(t_0 = \min(\text{all detection times})\) — earliest finish instant for that participant.
- **Window:** \([t_0,\, t_0 + 30\text{s}]\).
- **Official protocol finish:** \(\max(\text{detections with } t \le t_0 + 30\text{s})\).

Detections **after** \(t_0 + 30\text{s}\) remain stored (debug/audit) but **do not change** official protocol time. Outcomes exposed via **`RecordFinishDetectionOutcome`** (`protocolFinishTimeUpdated`, `detectionIgnoredForProtocolSeries`).

**Invariant:** **one participant ⇒ one official finish time** in protocol/UI/CSV — the aggregated fields on `RaceParticipantHashEntity`; multiple `FinishDetection` rows are expected.

Protocol XML and ordering use **official** finish time ascending. Protocol XML still emits one **embedding** string per finisher: the **first** row in **`participant_embeddings`** for that participant (fallback: legacy column).

---

## Schema migration (v7 → v8)

- New table **`participant_embeddings`** with FKs to **`races`** and **`race_participant_hashes`** (`ON DELETE CASCADE`).
- **`MIGRATION_7_8`** copies legacy non-failed **`race_participant_hashes.embedding`** into **`participant_embeddings`**, inferring **`FINISH_AUTO`** when **`sourcePhoto`** path contains **`finish_photos`**, otherwise **`START`**.
- Log line: **`AppDatabase`**: `MIGRATION_7_8 migrated participant_embeddings rows=<count>`.

---

## Package structure (high level)

| Area | Packages / locations | Responsibility |
|------|----------------------|----------------|
| **UI** | `ui.racelist`, `ui.racedetail`, `ui.identity`, `ui.camera`, `ui.scan`, `ui.util` | Race list (rounded **first pre-start** preview from stored `race_list_thumb.jpg` when present; `RaceRepository.ensureRaceListThumbnail`), race detail (**race start** time shown and editable via date/time pickers → `RaceRepository.updateRaceStartedAtEpochMillis`; timer unchanged), participant list with **row face-lookup** → pick a device scan code ranked by face match; **assigns** that code and `identity_registry` link to the row like **barcode scan**—`RaceRepository.assignParticipantToScannedIdentityFromFaceLookup` (scan + registry + `registryInfo`, thumbnail sync, then same duplicate-scan merge as `updateParticipantScan`); lookup lists **all device scan codes** with **max cosine** over historical vectors (registry + linked races’ `participant_embeddings`, donor row excluded from pool), **grouped by trimmed code**; **race-local photo sheet** excludes **`faces/`** crops; manual finish picks from **`finish_photos`** list), **identity registry** (scanned-only list + thumbnail backfill), **CameraX** multi-shot, barcode capture. |
| **Data** | `data.local` (Room), `data.repository`, `data.files`, `data.xml`, `data.model` | Persistence, `RaceRepository`, filesystem layout, mirrored XML. |
| **Domain** | `domain` (`RacePhotoProcessor`, `face`, `matching`, `time`, `identity`, `participants`, `debug`) | Orchestration, ML/embeddings, **multi-embedding match** (`ParticipantEmbeddingSet`, `FaceMatchEngine`), protocols — **no Android UI**. |
| **Export** | `export` | ZIP/CSV/version strings. |
| **App** | `VirtualVolunteerApp`, `MainActivity` | DI-style singletons, pipeline debug buffer. |

There is no separate `services/` module; cross-cutting behavior lives in the **repository**, **processor**, and **Application** pipeline log.

**`RaceRepository`** is the app-facing façade (same public methods as before); focused pieces live in **`internal`** types in the same package so they can depend on other **`internal`** helpers without leaking those types in public signatures: race lifecycle + `race.xml` (**`RaceLifecycleStore`**), per-race embedding read model for matching (**`RaceParticipantEmbeddingReader`**), event-photo delete + DB/XML side effects (**`RaceEventPhotoDeletionService`**), participant ↔ races history rows (**`ParticipantRaceHistoryReader`**), plus the existing writers/sync/merge types listed in **`CODE_EXPLORER.md`**.

---

## Race event photos (manual delete)

The **race detail** “all event photos” grid lists files under **`start_photos`** and **`finish_photos`** (`RaceEventPhotosLister`, `RaceRepository.listEventPhotoPaths`). **`RaceRepository.deleteRaceEventPhoto`** removes the file only when its canonical path is under those dirs for the race; it deletes **`finish_detections`** rows whose `sourcePhotoPath` matches, then **`recomputeProtocolFinishForParticipant`** for each affected participant; clears **`participant_embeddings.sourcePhotoPath`** when it matched; repoints **`race_participant_hashes.sourcePhoto`** / **`primaryThumbnailPhotoPath`** to an existing face crop when possible (otherwise blank source); clears **`races.lastPhotoPath`** when it matched; refreshes **`protocol.xml`**; and re-encodes **`race_list_thumb.jpg`** when a start photo under **`start_photos`** was removed.

---

## Exports (CSV)

- **Date and time** columns use `dd/MM/yyyy HH:mm:ss` in the device’s default zone (see `RaceUiFormatter.formatCsvDateTime`).
- **Participant code** in the participants export comes from the race row’s scan, or from the linked `identity_registry` row when the race row has no scan (so a code stored on the global identity still shows after a prior scan).

## Participants JSON (embeddings)

### Race bundle (`schemaVersion` 1)

- **`ParticipantsExportService.exportParticipantsWithEmbeddingsJson` / `importParticipantsWithEmbeddingsJson`** — JSON keyed by **`raceId`** with a **`participants`** array: each row has optional `barcode` / `name` and `embeddings` as numeric arrays.
- **Race import** is **additive** and **idempotent** on **protocol participants** for that race: match by trimmed scan = `barcode`; new barcodes create a `race_participant_hashes` row; each embedding is inserted only if no **identical** vector already exists (parsed double equality), including the legacy `race_participant_hashes.embedding` mirror when `participant_embeddings` is still empty. Nothing is deleted or overwritten.

### Device “scanned on this device” bundle (`schemaVersion` 2)

- **`exportDeviceScannedIdentitiesJson` / `importDeviceScannedIdentitiesJson`** — no `raceId`. Root has **`exportKind`** = `device_scanned_identities` and an **`identities`** array (same per-row `barcode` / `name` / `embeddings` shape). Export **groups** `identity_registry` rows by trimmed scan code and unions embeddings from the registry plus all **linked** race participants’ `participant_embeddings` (and legacy hash mirrors), like the face-lookup pool.
- **Device import** updates **`identity_registry` only** (never creates race protocol rows): merges **notes** from `name`; fills an **empty** registry **`embedding`** from the file when the device has **no** vector yet for that code in the union of registry + linked race embeddings. If the registry row **already** has a non-blank face embedding, **extra** file vectors are counted as skipped (single-vector field on `identity_registry`). Run **`consolidateAllScanMerges`** before and after device import in **`RaceRepository`**.

---

## Architectural rules (strict)

1. **Detection separate from hashing** — Face boxes come from ML Kit; descriptors from TFLite. Never conflate “no face” with “embedding failed.”
2. **Participant created even if hashing fails** — Insert `RaceParticipantHashEntity` with `embeddingFailed = true` and empty embedding when embedder throws; keep thumbnail when crop succeeded.
3. **One participant = one official finish time** — Stored as `protocolFinishTimeEpochMillis`; multiple finish **detections** refine the first-series aggregate but do not create multiple protocol rows per person.
4. **One participant, many embeddings** — Vectors live in `participant_embeddings`; matching and append semantics must keep **one** protocol row per person unless explicitly merged via duplicate scan handling.

---

## Offline testing behavior

- **Import start/finish folders** — Same processors as camera; timestamps from **`PhotoTimestampResolver`** (EXIF-first).
- **Build test protocol** — Walks finish folder, runs finish pipeline per file, appends trace to **`protocol_test_debug.log`** under the race directory.
- **Race gun helper** — `applyOfflineRaceStartFromStartPhotos` can set start time from start-photo EXIF max (offline workflows). After each **`ingestStartPhoto`** (camera or import), the repository runs the same helper so **`races.startedAtEpochMillis`** stays aligned with the start-photo folder.
- **Face embedding regression (JVM)** — Optional repo-root **`testdata/face_matching/`**; person crops may live under **`same_persons/<person_id>/`** (matches androidTest packaging) **or** directly under **`face_matching/<person_id>/`** (flat layout); **`different_persons/`** is ignored in the flat case; **`./gradlew faceEmbeddingRegressionTest`** runs the **`:face-embedding-regression`** JVM task (no device, no adb): loads **`app/src/main/assets/models/face_embedding.tflite`**, mirrors **`TfliteFaceEmbedder`** tensor preprocessing on disk crops (resize may differ slightly from Android **`Bitmap.createScaledBitmap`**), cosine vs **`FaceMatchEngine.DEFAULT_MIN_COSINE`** (override **`-PfaceRegressionMinCosine=`**), optional **`-PfaceTestMode=diagnostic`** (non-zero exit only in strict mode when pairs fail). Missing **`testdata/face_matching`** → **SKIPPED**, exit code 0. Reports: **`build/face-matching-report/same_person_cosine_report.csv`** and **`same_person_cosine_summary.txt`**. Does not change production matching or DB.
- **Face embedding regression (connected, optional)** — **`prepareFaceMatchingAndroidTestAssets`** still copies **`testdata/face_matching/`** into **`app/build/generated/face-matching-androidTest-assets/`** for **`ConnectedFaceEmbeddingRegressionTest`** (on-device **`TfliteFaceEmbedder`**, same comparator/report under app **`filesDir`**).

---

## Debug logging expectations

- **`RacePhotoProcessor`** emits structured lines to **`VirtualVolunteerApp.appendPipelineLog`** (ring buffer ~50 lines, also Logcat): start/finish phases, face counts, **multi-embedding** nearest/match cosines, duplicate skips, **`recordFinishDetection`** results, **`embeddingAppended`**, merge summaries from **`RaceRepository`** when duplicate scans collapse two rows.
- Finish **ingest** returns textual **`FinishProcessResult`** for batch/import tools.
- Optional **finish photo debug** UI builds **`FinishPhotoDebugReport`** (per-face nearest match vs threshold, pool availability).

Maintainers should preserve **traceability**: each finish match should be attributable to a detection row + aggregated protocol state.

---

## Related files (pointers only)

- Orchestration: `domain/RacePhotoProcessor.kt`
- Multi-embedding match: `domain/matching/FaceMatchEngine.kt`, `domain/matching/ParticipantEmbeddingSet.kt`
- Aggregation & protocol: `data/repository/RaceRepository.kt`, `data/repository/RaceProtocolFinishSync.kt`, `data/xml/ProtocolXmlIo.kt`
- Schema: `data/local/AppDatabase.kt`, `RaceParticipantHashEntity`, `ParticipantEmbeddingEntity`, `FinishDetectionEntity`
