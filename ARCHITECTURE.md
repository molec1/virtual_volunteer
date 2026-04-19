# Virtual Volunteer — Architecture

Concise system overview for developers and AI assistants. **Not** a class-by-class catalog.

## Purpose

Offline-first Android app for **race timing using photos**: capture **start-line** crowds (participants pool), **finish-line** faces (match + timestamps), optional scans/names, exports (ZIP, CSV, protocol XML). Designed for field use without assuming a live network.

---

## Core entities (Room)

| Concept | Persistence | Role |
|--------|-------------|------|
| **Race** | `RaceEntity` | One event: ids, timestamps (created / started / finished), status, GPS, folders, last processed photo path. |
| **Participant** | `RaceParticipantHashEntity` | One row per detected person in the race pool: face embedding (or failure flag), thumbnails, scan/name, identity-registry link, **`protocolFinishTimeEpochMillis`** / **`firstFinishSeenAtEpochMillis`** (official finish derived from detections). |
| **FinishDetection** | `FinishDetectionEntity` | One row per **matched** finish-face event: participant, race, **detectedAt**, source photo path, optional cosine score. Many rows per participant are allowed. |

**Official protocol finish time** lives on the participant row; detections are the raw timeline used to compute it (see aggregation below).

---

## Start photo pipeline (`RacePhotoProcessor.ingestStartPhoto`)

1. Layout folders; decode image with **EXIF upright** orientation (`OrientedPhotoBitmap`).
2. **Detection** — ML Kit faces on the bitmap (separate from embedding quality).
3. Per face: expand/crop region, save **JPEG thumbnail**, run **TFLite embedder**.
4. **Participant row** inserted even when embedding fails (`embeddingFailed`, empty embedding string) so the person stays visible/debuggable.
5. Optional **duplicate skip**: if embedding succeeds and cosine match against **existing pool** ≥ threshold, skip insert (same person on a later start photo).
6. Optional **global identity registry** resolution after embedding succeeds.
7. Update race **last processed photo** path.

**Invariant:** detection (boxes) ≠ embedding/hash; hashing failure must not skip creating the participant row.

---

## Finish photo pipeline (`RacePhotoProcessor.processFinishPhotoInternal`)

1. Same decode + detect faces on oriented bitmap.
2. Per face: crop → embed → **match** against **eligible pool** for **this photo only** (`availableThisPhoto`): cosine ≥ threshold; nearest-neighbour among participants not yet matched on **this** image (avoids double-assigning two faces to one identity in one frame).
3. **Cross-photo:** the same participant **can** match again on later finish photos (no global “already finished” exclusion).
4. Unmatched face above pool threshold but no slot left uses **create participant from finish** path (thumbnail + embedding + registry).
5. Each successful match calls **`RaceRepository.recordFinishDetection`** with finish instant (EXIF/session resolved time), photo path, cosine — **never** writes raw protocol time directly from a single detection without aggregation.

Pipeline logs include per-face detection, match cosine, and detection-record outcome.

---

## Finish aggregation (30-second first-series window)

Implemented in **`RaceRepository.recomputeProtocolFinishForParticipant`** (`FIRST_FINISH_SERIES_WINDOW_MS = 30_000`).

After **every** inserted `FinishDetection` for that participant:

- \(t_0 = \min(\text{all detection times})\) — earliest finish instant for that participant.
- **Window:** \([t_0,\, t_0 + 30\text{s}]\).
- **Official protocol finish:** \(\max(\text{detections with } t \le t_0 + 30\text{s})\).

Detections **after** \(t_0 + 30\text{s}\) remain stored (debug/audit) but **do not change** official protocol time. Outcomes exposed via **`RecordFinishDetectionOutcome`** (`protocolFinishTimeUpdated`, `detectionIgnoredForProtocolSeries`).

**Invariant:** **one participant ⇒ one official finish time** in protocol/UI/CSV — the aggregated fields on `RaceParticipantHashEntity`; multiple `FinishDetection` rows are expected.

Protocol XML and ordering use **official** finish time ascending.

---

## Package structure (high level)

| Area | Packages / locations | Responsibility |
|------|----------------------|----------------|
| **UI** | `ui.racelist`, `ui.racedetail`, `ui.identity`, `ui.scan`, `ui.util` | Race list (incl. **device identity registry** screen), race detail (participant **photo grid** bottom sheet on face tap; finish sources show a tick), barcode capture, previews. |
| **Data** | `data.local` (Room), `data.repository`, `data.files`, `data.xml`, `data.model` | Persistence, `RaceRepository`, filesystem layout, mirrored XML. |
| **Domain** | `domain` (`RacePhotoProcessor`, `face`, `matching`, `time`, `identity`, `participants`, `debug`) | Orchestration, ML/embeddings, geometry, protocols — **no Android UI**. |
| **Export** | `export` | ZIP/CSV/version strings. |
| **App** | `VirtualVolunteerApp`, `MainActivity` | DI-style singletons, pipeline debug buffer. |
| **Other** | `location` | Optional GPS at race creation. |

There is no separate `services/` module; cross-cutting behavior lives in the **repository**, **processor**, and **Application** pipeline log.

---

## Exports (CSV)

- **Date and time** columns use `dd/MM/yyyy HH:mm:ss` in the device’s default zone (see `RaceUiFormatter.formatCsvDateTime`).
- **Participant code** in the participants export comes from the race row’s scan, or from the linked `identity_registry` row when the race row has no scan (so a code stored on the global identity still shows after a prior scan).

---

## Architectural rules (strict)

1. **Detection separate from hashing** — Face boxes come from ML Kit; descriptors from TFLite. Never conflate “no face” with “embedding failed.”
2. **Participant created even if hashing fails** — Insert `RaceParticipantHashEntity` with `embeddingFailed = true` and empty embedding when embedder throws; keep thumbnail when crop succeeded.
3. **One participant = one official finish time** — Stored as `protocolFinishTimeEpochMillis`; multiple finish **detections** refine the first-series aggregate but do not create multiple protocol rows per person.

---

## Offline testing behavior

- **Import start/finish folders** — Same processors as camera; timestamps from **`PhotoTimestampResolver`** (EXIF-first).
- **Build test protocol** — Walks finish folder, runs finish pipeline per file, appends trace to **`protocol_test_debug.log`** under the race directory.
- **Race gun helper** — `applyOfflineRaceStartFromStartPhotos` can set start time from start-photo EXIF max (offline workflows).

---

## Debug logging expectations

- **`RacePhotoProcessor`** emits structured lines to **`VirtualVolunteerApp.appendPipelineLog`** (ring buffer ~50 lines, also Logcat): start/finish phases, face counts, match cosines, duplicate skips, **`recordFinishDetection`** results (official time updated vs ignored as late-series).
- Finish **ingest** returns textual **`FinishProcessResult`** for batch/import tools.
- Optional **finish photo debug** UI builds **`FinishPhotoDebugReport`** (per-face nearest match vs threshold, pool availability).

Maintainers should preserve **traceability**: each finish match should be attributable to a detection row + aggregated protocol state.

---

## Related files (pointers only)

- Orchestration: `domain/RacePhotoProcessor.kt`
- Aggregation & protocol: `data/repository/RaceRepository.kt`, `data/xml/ProtocolXmlIo.kt`
- Schema: `data/local/AppDatabase.kt`, `RaceParticipantHashEntity`, `FinishDetectionEntity`
