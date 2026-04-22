# Virtual Volunteer — code explorer

Quick map of **main concepts**, **where logic lives**, and **key UI + layouts**. For invariants, pipelines, and product rules, see [`ARCHITECTURE.md`](./ARCHITECTURE.md).

Paths are from `app/src/main/java/com/virtualvolunteer/app/` unless noted as `res/`.

---

## Core entities and persistence

| Concept | Room / model | Main files |
|--------|----------------|------------|
| **Race** | `RaceEntity` | `data/local/RaceEntity.kt`, `RaceDao.kt` |
| **Participant** (protocol person) | `RaceParticipantHashEntity` | `data/local/RaceParticipantHashEntity.kt`, `ParticipantHashDao.kt` |
| **Participant embedding** (many per participant) | `ParticipantEmbeddingEntity` | `data/local/ParticipantEmbeddingEntity.kt`, `EmbeddingSourceType.kt`, `ParticipantEmbeddingDao.kt` |
| **Finish detection** (one row per match event) | `FinishDetectionEntity` | `data/local/FinishDetectionEntity.kt`, `FinishDetectionDao.kt` |
| **Identity registry** (device-global face + optional scan) | `IdentityRegistryEntity` | `data/local/IdentityRegistryEntity.kt`, `IdentityRegistryDao.kt` |
| **Race status** | `RaceStatus` | `data/model/RaceStatus.kt` |
| **Dashboard list row** (read model, not a table) | `ParticipantDashboardRow` / `ParticipantDashboardDbRow` | `data/local/ParticipantDashboardRow.kt`, `ParticipantDashboardDbRow.kt` |

**Schema + migrations:** `data/local/AppDatabase.kt`, `data/local/Converters.kt`  
**On-disk layout:** `data/files/RacePaths.kt`  
**Mirrored race + protocol XML:** `data/xml/RaceXmlIo.kt`, `data/xml/RaceXmlSnapshot.kt`, `data/xml/ProtocolXmlIo.kt`

---

## Repository and orchestration

| Responsibility | File |
|----------------|------|
| CRUD, flows, exports, orchestration (delegates to helpers below) | `data/repository/RaceRepository.kt` |
| Race XML mirror write | `data/repository/RaceXmlWriter.kt` |
| Race list thumbnail + start-photo path checks | `data/repository/RaceListThumbnailHelper.kt` |
| Protocol finish aggregation + `protocol.xml` refresh | `data/repository/RaceProtocolFinishSync.kt` |
| Scan-code registry + participant merge / consolidation | `data/repository/ParticipantScanMergeCoordinator.kt` |
| Global identity + cosine-ranked scan lookup + registry thumbnail backfill | `data/repository/IdentityAssignmentHelper.kt` |
| Dashboard finish ranks (DB rows → UI rows) | `data/repository/RaceDashboardFinishRanks.kt` |
| Participant embedding insert/append/sync + primary thumbnail | `data/repository/RaceParticipantEmbeddingWriter.kt` |
| Participant race photos + finish-line image paths on disk | `data/repository/RaceParticipantMediaPaths.kt` |
| Finish detection insert + protocol outcome (manual + auto) | `data/repository/RaceFinishDetectionRecorder.kt` |
| Start + finish photo orchestration (delegates to ingest/pipeline/debug/test helpers) | `domain/RacePhotoProcessor.kt` |
| Start photo ingest (detect, crop, embed, insert, offline start) | `domain/StartPhotoIngestor.kt` |
| Finish photo pipeline (match pool, record detection, optional new participant) | `domain/FinishPhotoPipeline.kt` |
| Finish photo debug report (no persistence) | `domain/FinishPhotoDebugAnalyzer.kt` |
| Offline finish-folder test protocol log | `domain/FinishFolderTestProtocolBuilder.kt` |
| Finish batch result type | `domain/FinishProcessResult.kt` |
| **Lookup / match** (cosine, best-over-embeddings) | `domain/matching/FaceMatchEngine.kt`, `ParticipantEmbeddingSet.kt`, `MatchScore.kt` |
| **Participant pool** (Room-backed) | `domain/participants/RaceParticipantPool.kt`, `RoomRaceParticipantPool.kt` |
| **Identity** after start embedding | `domain/identity/GlobalIdentityResolution.kt` |
| **Time** (EXIF, etc.) | `domain/time/PhotoTimestampResolver.kt` |
| **Debug** (finish photo diagnostics) | `domain/debug/FinishPhotoDebugReport.kt` |
| **Face / ML** | `domain/face/`: `MlKitFaceDetector.kt`, `TfliteFaceEmbedder.kt` / `FaceEmbedder.kt`, `OrientedPhotoBitmap.kt`, `FaceThumbnailSaver.kt`, `FaceCropBounds.kt`, `EmbeddingMath.kt`, `FaceDebugOverlay.kt` |
| **Future** placeholder for known participants | `domain/future/FutureKnownParticipant.kt` |

---

## Export

| Output | File |
|--------|------|
| ZIP bundle | `export/RaceZipExporter.kt` |
| CSV | `export/RaceCsvExport.kt` |
| Version label strings | `export/ExportVersionLabel.kt` |

---

## UI: screens, logic, layouts

| Area | Kotlin | Layouts / list items (`res/layout/`) |
|------|--------|--------------------------------------|
| **Race list** | `ui/racelist/RaceListFragment.kt`, `RaceListAdapter.kt` | `fragment_race_list.xml`, `race_row.xml` |
| **Race detail** (orchestrates UI; imports/share/debug helpers alongside) | `ui/racedetail/RaceDetailFragment.kt`, `ParticipantDashboardAdapter.kt`, `RaceDetailPhotoBulkImporter.kt`, `RaceDetailShareHelper.kt`, `RaceDetailFinishDebugFormat.kt`, `RaceDetailParticipantSectionUi.kt` | `fragment_race_detail.xml`, `participant_dashboard_row.xml` |
| **Participant detail** | `ui/racedetail/ParticipantDetailFragment.kt` | `fragment_participant_detail.xml`, `item_participant_detail_race_row.xml` |
| **Face lookup** (assign scan from cosine-ranked codes) | `ui/racedetail/ParticipantLookupBottomSheet.kt`, `ParticipantLookupAdapter.kt`, `ParticipantLookupEmbeddings.kt` | `item_participant_lookup_result.xml` |
| **Participant / race photos** | `ui/racedetail/ParticipantPhotosBottomSheet.kt`, `ParticipantRacePhotoAdapter.kt`, `RaceParticipantPhotosBottomSheet.kt` | — (uses race-local paths; list thumbnails) |
| **Manual finish** | `ui/racedetail/ManualFinishInputBottomSheet.kt` | `item_manual_finish_photo.xml` |
| **Identity registry** | `ui/identity/IdentityRegistryFragment.kt`, `IdentityRegistryAdapter.kt` | `fragment_identity_registry.xml`, `item_identity_registry_row.xml` |
| **Camera** (multi-shot) | `ui/camera/CameraCaptureFragment.kt` | — (fragment host in nav) |
| **Barcode scan** | `ui/scan/BarcodeScanActivity.kt` | — |
| **Shared UI helpers** | `ui/util/RaceUiFormatter.kt`, `PreviewImageLoader.kt` | — |

**App shell:** `MainActivity.kt`, `VirtualVolunteerApp.kt` — `res/layout/activity_main.xml`  
**Strings / themes:** `res/values/strings.xml`, `themes.xml`, `styles.xml`, `colors.xml`

---

## “Lookup” in the codebase

- **Face lookup UI** — pick a device scan code to attach to a participant by face match: `ParticipantLookupBottomSheet.kt`, driven by `RaceRepository` (e.g. assign from lookup).
- **Matching** — similarity search over stored embeddings: `FaceMatchEngine.kt` + `ParticipantEmbeddingSet.kt` (used from `RacePhotoProcessor` and related flows).
- **Identity resolution** — linking new participants to `identity_registry`: `GlobalIdentityResolution.kt`, registry UI in `ui/identity/`.

---

## Maintenance

When you add a **new entity**, **significant screen**, or **move/rename** major files, update this document in the same change set (or immediately after) so the map stays accurate. Keep it a **table-of-contents** style index; do not duplicate `ARCHITECTURE.md` invariants.
