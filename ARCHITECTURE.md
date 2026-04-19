# Virtual Volunteer — Architecture

Concise system overview for developers and AI assistants. **Not** a class-by-class catalog.

## Purpose

Offline-first Android app for **race timing using photos**: capture **start-line** crowds (participants pool), **finish-line** faces (match + timestamps), optional scans/names, exports (ZIP, CSV, protocol XML). Designed for field use without assuming a live network.

---

## Core entities (Room)

| Concept | Persistence | Role |
|--------|-------------|------|
| **Race** | `RaceEntity` | One event: ids, timestamps (created / started / finished), status, GPS, folders, last processed photo path. |
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

Implemented in **`RaceRepository.recomputeProtocolFinishForParticipant`** (`FIRST_FINISH_SERIES_WINDOW_MS = 30_000`).

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
| **UI** | `ui.racelist`, `ui.racedetail`, `ui.identity`, `ui.scan`, `ui.util` | Race list (incl. **device identity registry** screen), race detail (participant **photo grid** bottom sheet on face tap; finish sources show a tick), barcode capture, previews. |
| **Data** | `data.local` (Room), `data.repository`, `data.files`, `data.xml`, `data.model` | Persistence, `RaceRepository`, filesystem layout, mirrored XML. |
| **Domain** | `domain` (`RacePhotoProcessor`, `face`, `matching`, `time`, `identity`, `participants`, `debug`) | Orchestration, ML/embeddings, **multi-embedding match** (`ParticipantEmbeddingSet`, `FaceMatchEngine`), protocols — **no Android UI**. |
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
4. **One participant, many embeddings** — Vectors live in `participant_embeddings`; matching and append semantics must keep **one** protocol row per person unless explicitly merged via duplicate scan handling.

---

## Offline testing behavior

- **Import start/finish folders** — Same processors as camera; timestamps from **`PhotoTimestampResolver`** (EXIF-first).
- **Build test protocol** — Walks finish folder, runs finish pipeline per file, appends trace to **`protocol_test_debug.log`** under the race directory.
- **Race gun helper** — `applyOfflineRaceStartFromStartPhotos` can set start time from start-photo EXIF max (offline workflows).

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
- Aggregation & protocol: `data/repository/RaceRepository.kt`, `data/xml/ProtocolXmlIo.kt`
- Schema: `data/local/AppDatabase.kt`, `RaceParticipantHashEntity`, `ParticipantEmbeddingEntity`, `FinishDetectionEntity`
