package com.virtualvolunteer.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.virtualvolunteer.app.BuildConfig
import com.virtualvolunteer.app.data.files.FaceCropManifestDisk
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.FaceCropBounds
import com.virtualvolunteer.app.domain.face.FaceDebugOverlay
import com.virtualvolunteer.app.domain.face.FaceEmbedder
import com.virtualvolunteer.app.domain.face.FaceGeometryFilters
import com.virtualvolunteer.app.domain.face.FaceThumbnailSaver
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap
import com.virtualvolunteer.app.domain.identity.GlobalIdentityResolution
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class StartPhotoIngestor(
    private val appContext: Context,
    private val races: RaceRepository,
    private val faces: MlKitFaceDetector,
    private val embedder: FaceEmbedder,
    private val matcher: FaceMatchEngine,
    private val decodeVisionBitmap: (File) -> Bitmap?,
    private val pipelineLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "RacePhotoProcessor"
    }

    /**
     * Stage 1: ML Kit on EXIF-upright bitmap. Stage 2: expand+crop, thumbnail, TFLite embedding.
     * Inserts a participant row whenever crop succeeds; [RaceParticipantHashEntity.embeddingFailed]
     * marks TFLite failures while keeping the row visible for debugging.
     */
    suspend fun ingest(raceId: String, photoFile: File): Result<Int> = runCatching {
        RacePaths.ensureRaceLayout(appContext, raceId)
        pipelineLog("—— ingestStartPhoto ——")
        pipelineLog("raceId=${raceId.take(8)}… sourceFile=${photoFile.name}")
        pipelineLog(OrientedPhotoBitmap.describeExifOrientation(photoFile))

        val bmp = decodeVisionBitmap(photoFile) ?: run {
            val msg = "DECODE_FAILED path=${photoFile.absolutePath}"
            Log.e(TAG, msg)
            pipelineLog(msg)
            return@runCatching 0
        }
        pipelineLog("visionBitmap=${bmp.width}x${bmp.height} (after EXIF upright correction)")

        val facesDir = RacePaths.facesDir(appContext, raceId)
        val debugDir = RacePaths.debugDir(appContext, raceId)
        debugDir.mkdirs()
        val createdAt = PhotoTimestampResolver.resolveEpochMillis(photoFile)
        val margin = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE

        try {
            val detected = faces.detectFaces(bmp)
            pipelineLog("detectedFaceCount=${detected.size}")
            Log.i(TAG, "ingestStartPhoto detectedFaceCount=${detected.size}")

            detected.forEachIndexed { i, face ->
                pipelineLog("detectorFace[${i + 1}] boundingBox=${face.boundingBox}")
                Log.i(TAG, "face[${i + 1}] bbox=${face.boundingBox}")
            }

            if (BuildConfig.DEBUG && detected.isNotEmpty()) {
                val overlayFile = File(
                    debugDir,
                    "start_${photoFile.nameWithoutExtension}_${System.currentTimeMillis()}.jpg",
                )
                val overlayOk = FaceDebugOverlay.saveAnnotatedCopy(
                    bmp,
                    detected,
                    margin,
                    overlayFile,
                )
                pipelineLog("debugOverlaySaved=$overlayOk file=${overlayFile.absolutePath}")
            }

            if (detected.isEmpty()) {
                pipelineLog("STOP: no faces (skip crop/embed/insert)")
                Log.w(TAG, "ingestStartPhoto no faces")
                return@runCatching 0
            }

            // Hard floor: unconditional minimum face area — identical to FinishPhotoPipeline.
            val afterHardFloor = detected.filter {
                it.boundingBox.width() * it.boundingBox.height() >= FinishPhotoPipeline.SMALL_FACE_HARD_FLOOR_PX2
            }
            val hardFloorSkipped = detected.size - afterHardFloor.size
            if (hardFloorSkipped > 0) {
                val msg = "startPhoto hardFloor skipped=$hardFloorSkipped floor=${FinishPhotoPipeline.SMALL_FACE_HARD_FLOOR_PX2}"
                pipelineLog(msg); Log.i(TAG, msg)
            }

            // Conditional relative + absolute filter — same calibration as finish photos.
            // In a pre-start crowd photo with one large face (e.g. 199 k px²) and many smaller
            // faces (30–40 k px²), effectiveCutoff = max(10 000, 199 000/3) = 66 400, so only
            // faces >= 66 400 px² are kept as participant seeds.  This prevents low-quality, small
            // faces in crowded start photos from seeding the embedding pool and causing spurious
            // finish-line matches.
            val maxRawArea = if (afterHardFloor.isEmpty()) 0
                             else afterHardFloor.maxOf { it.boundingBox.width() * it.boundingBox.height() }
            val filterSmall = maxRawArea >= FinishPhotoPipeline.SMALL_FACE_MIN_AREA_PX2
            val effectiveCutoff = if (filterSmall) {
                maxOf(FinishPhotoPipeline.SMALL_FACE_MIN_AREA_PX2,
                      maxRawArea / FinishPhotoPipeline.SMALL_FACE_RELATIVE_RATIO)
            } else 0
            val facesToProcess = if (filterSmall) {
                afterHardFloor.filter { it.boundingBox.width() * it.boundingBox.height() >= effectiveCutoff }
            } else {
                afterHardFloor
            }
            val smallSkipped = afterHardFloor.size - facesToProcess.size
            if (smallSkipped > 0) {
                val msg = "startPhoto smallFaceFilter skipped=$smallSkipped maxArea=$maxRawArea " +
                    "effectiveCutoff=$effectiveCutoff absMin=${FinishPhotoPipeline.SMALL_FACE_MIN_AREA_PX2} " +
                    "ratio=${FinishPhotoPipeline.SMALL_FACE_RELATIVE_RATIO}"
                pipelineLog(msg); Log.i(TAG, msg)
            }

            if (facesToProcess.isEmpty()) {
                pipelineLog("STOP: no faces after size filter (skip crop/embed/insert)")
                return@runCatching 0
            }

            // Peripheral filter: strict central band, with a peer-group exception for
            // 2–4 similarly sized frontal faces (see FaceGeometryFilters).
            val facesToSeed = facesToProcess.filter { face ->
                val box = face.boundingBox
                val cxNorm = (box.left + box.right) / 2f / bmp.width
                val aspect = if (box.height() <= 0) 1f else box.width().toFloat() / box.height()
                FaceGeometryFilters.keepStartPeripheral(cxNorm, aspect, facesToProcess.size)
            }
            val peripheralSkipped = facesToProcess.size - facesToSeed.size
            if (peripheralSkipped > 0) {
                val msg = "startPhoto peripheralFilter skipped=$peripheralSkipped " +
                    "hardX=${FaceGeometryFilters.START_PERIPHERAL_HARD_X} " +
                    "peerMax=${FaceGeometryFilters.PEER_GROUP_MAX_CX_HALF} " +
                    "group=${facesToProcess.size}"
                pipelineLog(msg); Log.i(TAG, msg)
            }
            if (facesToSeed.isEmpty()) {
                pipelineLog("STOP: no faces after peripheral filter (skip crop/embed/insert)")
                return@runCatching 0
            }

            var inserted = 0
            facesToSeed.forEachIndexed { index, face ->
                val faceNum = index + 1
                val raw = Rect(face.boundingBox)
                val expanded = FaceCropBounds.expandFaceRect(raw, bmp.width, bmp.height, margin)
                pipelineLog("face#$faceNum rawBoundingBox=$raw expandedBoundingBox=$expanded marginPerSide=$margin")

                val crop = FaceCropBounds.cropBitmap(bmp, expanded)
                pipelineLog("face#$faceNum cropSucceeded=${crop != null}")
                if (crop == null) {
                    Log.w(TAG, "ingestStartPhoto face#$faceNum crop_failed")
                    return@forEachIndexed
                }

                val thumbFile = FaceThumbnailSaver.thumbnailFile(facesDir, photoFile, faceNum)
                var thumbnailSaved = false
                try {
                    FaceThumbnailSaver.saveJpeg(crop, thumbFile)
                    thumbnailSaved = thumbFile.exists() && thumbFile.length() > 0L
                } catch (e: Exception) {
                    Log.e(TAG, "thumbnail save failed face#$faceNum", e)
                    pipelineLog("face#$faceNum thumbnailSaved=false err=${e.message}")
                }
                pipelineLog("face#$faceNum thumbnailSaved=$thumbnailSaved path=${thumbFile.absolutePath}")

                val embedResult = runCatching {
                    withContext(Dispatchers.Default) { embedder.embed(crop) }
                }
                crop.recycle()

                val vec = embedResult.getOrNull()
                val embeddingFailed = vec == null
                val embeddingStr = vec?.let { EmbeddingMath.formatCommaSeparated(it) } ?: ""

                if (embeddingFailed) {
                    embedResult.exceptionOrNull()?.let { err ->
                        Log.e(TAG, "embedding failed face#$faceNum", err)
                        pipelineLog("face#$faceNum descriptorCreated=false err=${err.message}")
                    }
                } else {
                    pipelineLog("face#$faceNum descriptorCreated=true dim=${vec.size}")
                }

                if (!embeddingFailed) {
                    val blacklist = races.getEmbeddingMatchBlacklistSnapshot()
                    val existingSets = races.listParticipantEmbeddingSets(raceId).filter { it.hasEmbeddings }
                    val duplicateOf = matcher.match(vec, embeddingStr, existingSets, blacklist)
                    if (duplicateOf != null) {
                        if (thumbFile.exists()) thumbFile.delete()
                        pipelineLog(
                            "face#$faceNum skip_duplicate_of_participant id=${duplicateOf.id} " +
                                "(same_face_as_existing_pool)",
                        )
                        Log.i(TAG, "ingestStartPhoto face#$faceNum skipped duplicate id=${duplicateOf.id}")
                        return@forEachIndexed
                    }
                }

                val globalId: GlobalIdentityResolution? =
                    if (!embeddingFailed) races.resolveGlobalIdentity(vec) else null

                val rowId = races.insertParticipantHash(
                    RaceParticipantHashEntity(
                        raceId = raceId,
                        embedding = embeddingStr,
                        embeddingFailed = embeddingFailed,
                        sourcePhoto = photoFile.absolutePath,
                        faceThumbnailPath = thumbFile.absolutePath,
                        scannedPayload = null,
                        registryInfo = globalId?.registryInfo,
                        identityRegistryId = globalId?.registryId,
                        displayName = null,
                        createdAtEpochMillis = createdAt,
                    ),
                    initialEmbeddingSource = EmbeddingSourceType.START,
                    primaryThumbnailPhotoPath = if (thumbnailSaved) thumbFile.absolutePath else null,
                )
                globalId?.let { g ->
                    pipelineLog(
                        "face#$faceNum identityRegistry id=${g.registryId} matchedExisting=${g.matchedExisting} " +
                            "info=${g.registryInfo ?: "—"}",
                    )
                }
                val total = races.countParticipantsForRace(raceId)
                pipelineLog(
                    "face#$faceNum participantRowInserted=true id=$rowId embeddingFailed=$embeddingFailed " +
                        "totalParticipants=$total",
                )
                Log.i(
                    TAG,
                    "insert id=$rowId embeddingFailed=$embeddingFailed totalParticipants=$total",
                )
                FaceCropManifestDisk.upsertReplaceParticipantOnSource(
                    appContext,
                    raceId,
                    FaceCropManifestDisk.Entry(
                        sourcePhotoPath = photoFile.absolutePath,
                        visionWidth = bmp.width,
                        visionHeight = bmp.height,
                        left = expanded.left,
                        top = expanded.top,
                        right = expanded.right,
                        bottom = expanded.bottom,
                        participantHashId = rowId,
                        cropFilePath = thumbFile.takeIf { it.exists() }?.absolutePath,
                    ),
                )
                inserted++
            }

            pipelineLog("ingestStartPhoto done insertedRows=$inserted")
            inserted
        } finally {
            races.updateLastProcessedPhoto(raceId, photoFile.absolutePath)
            races.applyOfflineRaceStartFromStartPhotos(raceId)
            bmp.recycle()
        }
    }
}
