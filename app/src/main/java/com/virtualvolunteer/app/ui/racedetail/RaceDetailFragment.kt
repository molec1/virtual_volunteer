package com.virtualvolunteer.app.ui.racedetail

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.model.RaceStatus
import com.virtualvolunteer.app.databinding.FragmentRaceDetailBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.debug.FinishPhotoDebugReport
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RoomRaceParticipantPool
import com.virtualvolunteer.app.export.RaceZipExporter
import com.virtualvolunteer.app.ui.scan.BarcodeScanActivity
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Race control: timer, pre-start / finish photos, offline test tools, export.
 */
class RaceDetailFragment : Fragment() {

    private var _binding: FragmentRaceDetailBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private lateinit var faceDetector: MlKitFaceDetector
    private lateinit var faceEmbedder: TfliteFaceEmbedder
    private lateinit var photoProcessor: RacePhotoProcessor

    private lateinit var participantAdapter: ParticipantDashboardAdapter

    private var latestRace: RaceEntity? = null

    private var pendingScanParticipantId: Long? = null

    private val barcodeScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pid = pendingScanParticipantId
        pendingScanParticipantId = null
        if (result.resultCode != Activity.RESULT_OK || pid == null) return@registerForActivityResult
        val text = extractScanText(result.data) ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            (requireActivity().application as VirtualVolunteerApp).raceRepository.updateParticipantScan(
                raceId,
                pid,
                text,
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val race = latestRace
            val startedAt = race?.startedAtEpochMillis
            if (startedAt != null && _binding != null) {
                val end = race.finishedAtEpochMillis ?: System.currentTimeMillis()
                binding.timerValue.text = RaceUiFormatter.formatElapsed((end - startedAt).coerceAtLeast(0L))
                if (race.finishedAtEpochMillis == null) {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingCameraAction
        pendingCameraAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(requireContext(), R.string.permission_camera_required, Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingCameraAction: (() -> Unit)? = null

    private lateinit var takeStartPictureLauncher: androidx.activity.result.ActivityResultLauncher<Uri>
    private lateinit var takeFinishPictureLauncher: androidx.activity.result.ActivityResultLauncher<Uri>
    private var pendingStartPhotoFile: File? = null
    private var pendingFinishPhotoFile: File? = null

    private val pickMultipleStartDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val dir = RacePaths.startPhotosDir(ctx, raceId)
            var files = 0
            var hashes = 0
            for (uri in uris) {
                try {
                    val name = UriFileCopy.displayName(ctx, uri)
                    val dest = RacePhotoProcessor.uniqueImportedFile(dir, name)
                    UriFileCopy.copyToFile(ctx, uri, dest)
                    files++
                    val result = photoProcessor.ingestStartPhoto(raceId, dest)
                    result.onSuccess { count -> hashes += count }
                } catch (t: Throwable) {
                    Log.w(TAG, "import start photo failed", t)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    "Imported $files file(s). Participant rows added: $hashes",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private val pickMultipleFinishDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val app = ctx as VirtualVolunteerApp
            val repo = app.raceRepository
            val dir = RacePaths.finishPhotosDir(ctx, raceId)
            var copied = 0
            var finishRowsAdded = 0
            var ingestFailures = 0
            var lastDest: File? = null
            app.appendPipelineLog("—— importFinishPhotos (${uris.size} uri(s)) ——")
            for (uri in uris) {
                try {
                    val name = UriFileCopy.displayName(ctx, uri)
                    val dest = RacePhotoProcessor.uniqueImportedFile(dir, name)
                    UriFileCopy.copyToFile(ctx, uri, dest)
                    copied++
                    lastDest = dest
                    val ingest = photoProcessor.ingestFinishPhoto(raceId, dest)
                    ingest.onSuccess { n -> finishRowsAdded += n }
                    ingest.onFailure {
                        ingestFailures++
                        Log.w(TAG, "ingestFinishPhoto failed for ${dest.name}", it)
                        app.appendPipelineLog("IMPORT_INGEST_FAILED file=${dest.name} err=${it.message}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "import finish photo failed", t)
                    ingestFailures++
                    app.appendPipelineLog("IMPORT_COPY_FAILED err=${t.message}")
                }
            }
            lastDest?.let { repo.updateLastProcessedPhoto(raceId, it.absolutePath) }
            app.appendPipelineLog(
                "importFinishPhotos done copied=$copied finishRowsAdded=$finishRowsAdded failures=$ingestFailures",
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    "Finish photos: copied $copied, finish rows added $finishRowsAdded" +
                        if (ingestFailures > 0) ", failures $ingestFailures" else "",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private val pickSingleFinishDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val tmp = File(ctx.cacheDir, "single_finish_debug_${System.currentTimeMillis()}.img")
            try {
                UriFileCopy.copyToFile(ctx, uri, tmp)
                val report = photoProcessor.analyzeFinishPhotoDebug(raceId, tmp)
                val text = report.fold(
                    onSuccess = { formatFinishDebugReport(it) },
                    onFailure = { "Analysis failed: ${it.message ?: "unknown"}" },
                )
                Log.i(TAG, text)
                withContext(Dispatchers.Main) {
                    showScrollableDialog(getString(R.string.finish_debug_dialog_title), text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "single finish debug failed", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                tmp.delete()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        faceDetector = MlKitFaceDetector()
        faceEmbedder = TfliteFaceEmbedder(requireActivity().applicationContext)
        photoProcessor = RacePhotoProcessor(
            races = repo,
            faces = faceDetector,
            embedder = faceEmbedder,
            matcher = FaceMatchEngine(),
            pool = RoomRaceParticipantPool(repo),
            appContext = requireActivity().applicationContext,
        )

        takeStartPictureLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { ok ->
            val file = pendingStartPhotoFile
            pendingStartPhotoFile = null
            if (!ok || file == null || !file.exists()) {
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = photoProcessor.ingestStartPhoto(raceId, file)
                withContext(Dispatchers.Main) {
                    result.onSuccess { count ->
                        Toast.makeText(
                            requireContext(),
                            "Participants added: $count",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }.onFailure {
                        Toast.makeText(
                            requireContext(),
                            "Start photo processing failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }

        takeFinishPictureLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { ok ->
            val file = pendingFinishPhotoFile
            pendingFinishPhotoFile = null
            if (!ok || file == null || !file.exists()) {
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = photoProcessor.ingestFinishPhoto(raceId, file)
                withContext(Dispatchers.Main) {
                    result.onSuccess { count ->
                        Toast.makeText(
                            requireContext(),
                            "Finish rows added: $count",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }.onFailure {
                        Toast.makeText(
                            requireContext(),
                            "Finish photo processing failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRaceDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as VirtualVolunteerApp
        val repo = app.raceRepository

        participantAdapter = ParticipantDashboardAdapter(
            onScanCode = { id -> openBarcodeScan(id) },
            onRemove = { id -> confirmRemoveParticipant(id) },
        )
        binding.participantsRecycler.adapter = participantAdapter
        binding.participantsRecycler.itemAnimator = null

        app.pipelineDebugLines.observe(
            viewLifecycleOwner,
            Observer { lines ->
                binding.pipelineDebugText.text = lines.joinToString("\n")
            },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.observeRace(raceId).collect { race ->
                        latestRace = race
                        if (race != null) {
                            bindLastPhotoPreview(race.lastPhotoPath)
                            renderUi(race)
                        }
                    }
                }
                launch {
                    combine(
                        repo.observeParticipantDashboard(raceId),
                        repo.observeFinishRecordCount(raceId),
                    ) { rows, finishN -> rows to finishN }
                        .collect { (rows, finishN) ->
                            participantAdapter.submitList(rows)
                            val showProtocol = rows.isNotEmpty() || finishN > 0
                            binding.dashboardParticipantsTitle.visibility =
                                if (showProtocol) View.VISIBLE else View.GONE
                            binding.participantsRecycler.visibility =
                                if (showProtocol) View.VISIBLE else View.GONE
                        }
                }
            }
        }

        binding.btnStartRace.setOnClickListener { onStartRaceClicked() }
        binding.btnPreStartPhoto.setOnClickListener { onPreStartPhotoClicked() }
        binding.btnTakeFinishPhoto.setOnClickListener { onTakeFinishPhotoClicked() }
        binding.btnFinishRace.setOnClickListener { onFinishRaceClicked() }
        binding.btnExport.setOnClickListener { onExportClicked() }

        binding.btnImportStartPhotos.setOnClickListener {
            pickMultipleStartDocuments.launch(arrayOf("image/*"))
        }
        binding.btnImportFinishPhotos.setOnClickListener {
            pickMultipleFinishDocuments.launch(arrayOf("image/*"))
        }
        binding.btnBuildTestProtocol.setOnClickListener { onBuildTestProtocolClicked() }
        binding.btnTestSingleFinishPhoto.setOnClickListener {
            pickSingleFinishDocument.launch(arrayOf("image/*"))
        }
    }

    private fun renderUi(race: RaceEntity) {
        binding.raceIdText.text = getString(R.string.race_detail_title) + ": " + race.id.take(8) + "…"
        binding.createdText.text = getString(R.string.race_created_label) + ": " +
            RaceUiFormatter.formatDate(race.createdAtEpochMillis) + " " +
            RaceUiFormatter.formatTime(race.createdAtEpochMillis)

        val lat = race.latitude
        val lon = race.longitude
        binding.locationText.text = if (lat != null && lon != null) {
            getString(R.string.race_location_label) + ": %.5f, %.5f".format(lat, lon)
        } else {
            getString(R.string.race_location_label) + ": unavailable"
        }

        binding.statusText.text = getString(R.string.race_status_label) + ": " + RaceUiFormatter.formatStatus(race.status)

        val started = race.startedAtEpochMillis != null
        binding.timerRow.visibility = if (started) View.VISIBLE else View.GONE
        if (started) {
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
        } else {
            handler.removeCallbacks(timerRunnable)
        }

        binding.btnStartRace.visibility = if (race.status == RaceStatus.CREATED) View.VISIBLE else View.GONE
        binding.btnPreStartPhoto.visibility = if (race.status == RaceStatus.CREATED) View.VISIBLE else View.GONE
        binding.btnPreStartPhoto.isEnabled = race.status == RaceStatus.CREATED

        val postStartVisible = race.status != RaceStatus.CREATED
        binding.postStartGroup.visibility = if (postStartVisible) View.VISIBLE else View.GONE

        binding.btnTakeFinishPhoto.isEnabled =
            race.status == RaceStatus.STARTED || race.status == RaceStatus.RECORDING

        binding.btnFinishRace.isEnabled = race.status != RaceStatus.FINISHED && race.status != RaceStatus.EXPORTED
    }

    private fun bindLastPhotoPreview(path: String?) {
        if (path.isNullOrBlank()) {
            binding.lastPhotoPreview.setImageBitmap(null)
            binding.lastPhotoPreview.setBackgroundResource(R.drawable.bg_placeholder_photo)
            binding.lastPhotoEmptyHint.visibility = View.VISIBLE
            return
        }
        binding.lastPhotoEmptyHint.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 1080)
            withContext(Dispatchers.Main) {
                binding.lastPhotoPreview.background = null
                binding.lastPhotoPreview.setImageBitmap(bmp)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(timerRunnable)
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::faceDetector.isInitialized) {
            faceDetector.close()
        }
        if (this::faceEmbedder.isInitialized) {
            faceEmbedder.close()
        }
    }

    companion object {
        const val ARG_RACE_ID = "raceId"
        private const val TAG = "RaceDetail"
    }

    fun onStartRaceClicked() {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        lifecycleScope.launch(Dispatchers.IO) {
            repo.markStarted(raceId, System.currentTimeMillis())
        }
    }

    fun onPreStartPhotoClicked() {
        runWithCameraPermission {
            try {
                val file = RacePhotoProcessor.defaultOutputStartPhotoFile(requireContext(), raceId)
                pendingStartPhotoFile = file
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file,
                )
                takeStartPictureLauncher.launch(uri)
            } catch (_: Throwable) {
                Toast.makeText(requireContext(), "Unable to start camera capture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onTakeFinishPhotoClicked() {
        runWithCameraPermission {
            try {
                val file = RacePhotoProcessor.defaultOutputFinishPhotoFile(requireContext(), raceId)
                pendingFinishPhotoFile = file
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file,
                )
                takeFinishPictureLauncher.launch(uri)
            } catch (_: Throwable) {
                Toast.makeText(requireContext(), "Unable to start camera capture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onFinishRaceClicked() {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        lifecycleScope.launch(Dispatchers.IO) {
            repo.markFinished(raceId, System.currentTimeMillis())
        }
    }

    fun onBuildTestProtocolClicked() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val result = photoProcessor.buildTestProtocolFromFinishFolder(ctx, raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { logFile ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.test_protocol_built) + "\n${logFile.name}",
                        Toast.LENGTH_LONG,
                    ).show()
                    Log.i(TAG, "Test protocol log: ${logFile.absolutePath}")
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.test_protocol_failed, Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "build test protocol failed", it)
                }
            }
        }
    }

    fun onExportClicked() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = RaceZipExporter.exportRaceFolder(requireContext().applicationContext, raceId)
            val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
            withContext(Dispatchers.Main) {
                result.onSuccess { zip ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        repo.markExported(raceId)
                    }
                    shareZip(zip)
                    Toast.makeText(requireContext(), R.string.export_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareZip(zip: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            zip,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share race export"))
    }

    private fun runWithCameraPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openBarcodeScan(participantId: Long) {
        pendingScanParticipantId = participantId
        runWithCameraPermission {
            barcodeScanLauncher.launch(Intent(requireContext(), BarcodeScanActivity::class.java))
        }
    }

    private fun extractScanText(data: Intent?): String? {
        if (data == null) return null
        val parsed = IntentIntegrator.parseActivityResult(
            IntentIntegrator.REQUEST_CODE,
            Activity.RESULT_OK,
            data,
        )
        if (parsed != null && parsed.contents != null) return parsed.contents
        data.getStringExtra("SCAN_RESULT")?.let { return it }
        return null
    }

    private fun confirmRemoveParticipant(participantId: Long) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_participant_title)
            .setMessage(R.string.delete_participant_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    (requireActivity().application as VirtualVolunteerApp).raceRepository
                        .removeParticipantFromRace(raceId, participantId)
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.RED)
        }
        dialog.show()
    }

    private fun formatFinishDebugReport(report: FinishPhotoDebugReport): String {
        val sb = StringBuilder()
        sb.appendLine("file=${report.photoPath}")
        sb.appendLine("detectedFaces=${report.detectedFaceCount}")
        sb.appendLine("---")
        for (face in report.faces) {
            sb.appendLine("Face #${face.faceIndex}")
            sb.appendLine("  embeddingPreview=${face.embeddingPreview}")
            sb.appendLine(
                "  nearestParticipantId=${face.nearestParticipantId} " +
                    "nearestStoredEmbeddingPreview=${face.nearestParticipantEmbeddingPreview}",
            )
            sb.appendLine(
                "  cosineSimilarity=${face.cosineSimilarity} " +
                    "threshold=${face.cosineThreshold} passesThreshold=${face.passesThreshold}",
            )
            sb.appendLine("  participantAlreadyFinished=${face.participantAlreadyFinished}")
            sb.appendLine("  wouldRecordAsNewFinish=${face.wouldRecordAsNewFinish}")
            sb.appendLine("---")
        }
        return sb.toString().trimEnd()
    }

    private fun showScrollableDialog(title: String, message: String) {
        val scroll = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            setPadding(48, 32, 48, 32)
            text = message
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
