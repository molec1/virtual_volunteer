package com.virtualvolunteer.app.ui.racedetail

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.model.RaceStatus
import com.virtualvolunteer.app.databinding.FragmentRaceDetailBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RoomRaceParticipantPool
import com.virtualvolunteer.app.export.RaceZipExporter
import com.virtualvolunteer.app.ui.camera.CameraCaptureFragment
import com.virtualvolunteer.app.ui.scan.BarcodeScanActivity
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Race control: timer, pre-start / finish photos, offline test tools, export.
 */
class RaceDetailFragment : Fragment() {

    private var _binding: FragmentRaceDetailBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    // Expanded state for collapsible sections
    private var offlineTestExpanded = false
    private var participantsExpanded = true
    private var pipelineDebugExpanded = false

    private lateinit var faceDetector: MlKitFaceDetector
    private lateinit var faceEmbedder: TfliteFaceEmbedder
    private lateinit var photoProcessor: RacePhotoProcessor
    private lateinit var photoImports: RaceDetailPhotoBulkImporter

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

    private val pickMultipleStartDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            photoImports.importStartPhotoUris(uris)
            withContext(Dispatchers.Main) {
                // Removed Toast confirmation
            }
        }
    }

    private val pickMultipleFinishDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            photoImports.importFinishPhotoUris(uris)
            withContext(Dispatchers.Main) {
                // Removed Toast confirmation
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
                    onSuccess = { formatFinishPhotoDebugReport(it) },
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
        photoImports = RaceDetailPhotoBulkImporter(
            requireActivity().application as VirtualVolunteerApp,
            raceId,
            photoProcessor,
        )
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
            onEditDisplayName = { id, current -> showParticipantNameEditor(id, current) },
            onOpenPhotos = { id -> openRaceParticipantPhotos(raceId, id) },
            onFaceLookup = { id -> openParticipantLookupForRow(id) },
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
                            renderUi(race)
                            renderCollapsibleSections()
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
                            RaceDetailParticipantSectionUi.applyParticipantSectionVisibility(
                                binding,
                                participantRowCount = rows.size,
                                finishRecordCount = finishN,
                                participantsExpanded = participantsExpanded,
                            )
                        }
                }
            }
        }

        binding.btnStartRace.setOnClickListener { onStartRaceClicked() }
        binding.btnPreStartPhoto.setOnClickListener { onPreStartPhotoClicked() }
        binding.btnTakeFinishPhoto.setOnClickListener { onTakeFinishPhotoClicked() }
        binding.btnFinishRace.setOnClickListener { onFinishRaceClicked() }
        binding.btnExport.setOnClickListener { onExportClicked() }
        binding.btnExportTimesCsv.setOnClickListener { onExportTimesCsvClicked() }
        binding.btnExportParticipantsCsv.setOnClickListener { onExportParticipantsCsvClicked() }

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

        binding.btnAddManualFinish.setOnClickListener { openManualFinishBottomSheet() }

        binding.btnEditRaceStartTime.setOnClickListener { showEditRaceStartTimeDialog() }

        // Collapsible section headers
        binding.offlineTestHeaderLayout.setOnClickListener { toggleOfflineTestExpansion() }
        binding.participantsHeaderLayout.setOnClickListener { toggleParticipantsExpansion() }
        binding.pipelineDebugHeaderLayout.setOnClickListener { togglePipelineDebugExpansion() }
    }

    override fun onResume() {
        super.onResume()
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.consolidateScanMergesForRace(raceId) }
            val rows = withContext(Dispatchers.IO) { repo.getParticipantDashboardUi(raceId) }
            val finishN = withContext(Dispatchers.IO) { repo.finishRecordCount(raceId) }
            if (_binding == null) return@launch
            participantAdapter.submitList(rows)
            RaceDetailParticipantSectionUi.applyParticipantSectionVisibility(
                binding,
                participantRowCount = rows.size,
                finishRecordCount = finishN,
                participantsExpanded = participantsExpanded,
            )
        }
    }

    private fun renderUi(race: RaceEntity) {
        binding.raceIdText.text = getString(R.string.race_detail_title) + ": " + race.id.take(8) + "…"
        binding.createdText.text = getString(R.string.race_created_label) + ": " +
            RaceUiFormatter.formatDate(race.createdAtEpochMillis) + " " +
            RaceUiFormatter.formatTime(race.createdAtEpochMillis)

        binding.statusText.text = getString(R.string.race_status_label) + ": " + RaceUiFormatter.formatStatus(race.status)

        val startMs = race.startedAtEpochMillis
        binding.raceStartTimeValue.text = if (startMs != null) {
            RaceUiFormatter.formatDateTime(startMs)
        } else {
            getString(R.string.race_start_time_not_set)
        }

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

    private fun renderCollapsibleSections() {
        binding.offlineTestContent.visibility = if (offlineTestExpanded) View.VISIBLE else View.GONE
        binding.offlineTestExpandIcon.setImageResource(if (offlineTestExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)

        binding.participantsContent.visibility = if (participantsExpanded) View.VISIBLE else View.GONE
        binding.participantsExpandIcon.setImageResource(if (participantsExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)

        binding.pipelineDebugContent.visibility = if (pipelineDebugExpanded) View.VISIBLE else View.GONE
        binding.pipelineDebugExpandIcon.setImageResource(if (pipelineDebugExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
    }

    private fun toggleOfflineTestExpansion() {
        offlineTestExpanded = !offlineTestExpanded
        renderCollapsibleSections()
    }

    private fun toggleParticipantsExpansion() {
        participantsExpanded = !participantsExpanded
        renderCollapsibleSections()
    }

    private fun togglePipelineDebugExpansion() {
        pipelineDebugExpanded = !pipelineDebugExpanded
        renderCollapsibleSections()
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
        const val ARG_PARTICIPANT_ID = "participantId"
        private const val TAG = "RaceDetail"
    }

    fun onStartRaceClicked() {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        lifecycleScope.launch(Dispatchers.IO) {
            repo.markStarted(raceId, System.currentTimeMillis())
        }
    }

    private fun showEditRaceStartTimeDialog() {
        val race = latestRace ?: return
        val cal = Calendar.getInstance()
        race.startedAtEpochMillis?.let { cal.timeInMillis = it }

        val ctx = requireContext()
        DatePickerDialog(
            ctx,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                TimePickerDialog(
                    ctx,
                    { _, h, min ->
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, min)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        lifecycleScope.launch(Dispatchers.IO) {
                            (requireActivity().application as VirtualVolunteerApp).raceRepository
                                .updateRaceStartedAtEpochMillis(raceId, cal.timeInMillis)
                        }
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    fun onPreStartPhotoClicked() {
        findNavController().navigate(
            R.id.action_race_detail_to_cameraCaptureFragment,
            bundleOf(
                CameraCaptureFragment.ARG_RACE_ID to raceId,
                CameraCaptureFragment.ARG_CAPTURE_MODE to CameraCaptureFragment.MODE_START_PHOTO,
            ),
        )
    }

    fun onTakeFinishPhotoClicked() {
        findNavController().navigate(
            R.id.action_race_detail_to_cameraCaptureFragment,
            bundleOf(
                CameraCaptureFragment.ARG_RACE_ID to raceId,
                CameraCaptureFragment.ARG_CAPTURE_MODE to CameraCaptureFragment.MODE_FINISH_PHOTO,
            ),
        )
    }

    fun onFinishRaceClicked() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.finish_race_confirm_title)
            .setMessage(R.string.finish_race_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_finish) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    (requireActivity().application as VirtualVolunteerApp).raceRepository
                        .markFinished(raceId, System.currentTimeMillis())
                }
            }
            .show()
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
                    RaceDetailShareHelper.shareZip(requireContext(), zip)
                    Toast.makeText(requireContext(), R.string.export_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onExportTimesCsvClicked() {
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
            val result = repo.exportTimesCsv(raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { file ->
                    RaceDetailShareHelper.shareCsv(requireContext(), file, getString(R.string.export_csv_times_saved))
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.export_csv_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onExportParticipantsCsvClicked() {
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
            val result = repo.exportParticipantsCsv(raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { file ->
                    RaceDetailShareHelper.shareCsv(requireContext(), file, getString(R.string.export_csv_participants_saved))
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.export_csv_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showParticipantNameEditor(participantId: Long, currentName: String?) {
        val input = EditText(requireContext()).apply {
            setText(currentName.orEmpty())
            hint = getString(R.string.participant_tap_to_name)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.participant_name_dialog_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()
                lifecycleScope.launch(Dispatchers.IO) {
                    (requireActivity().application as VirtualVolunteerApp).raceRepository
                        .updateParticipantDisplayName(raceId, participantId, text)
                }
            }
            .show()
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

    private fun openParticipantPhotos(participantId: Long) {
        ParticipantPhotosBottomSheet.newInstance(raceId, participantId)
            .show(childFragmentManager, "participantPhotos")
    }

    private fun openParticipantLookupForRow(participantId: Long) {
        ParticipantLookupBottomSheet.newInstance(raceId, donorParticipantId = participantId)
            .show(childFragmentManager, "participantLookupRow")
    }

    private fun openManualFinishBottomSheet() {
        ManualFinishInputBottomSheet.newInstance(raceId)
            .show(childFragmentManager, "manualFinishInput")
    }

    private fun openRaceParticipantPhotos(raceId: String, participantId: Long) {
        RaceParticipantPhotosBottomSheet.newInstance(raceId, participantId)
            .show(childFragmentManager, "raceParticipantPhotos")
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
