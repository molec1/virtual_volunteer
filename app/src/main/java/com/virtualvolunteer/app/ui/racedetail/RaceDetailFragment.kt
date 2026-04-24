package com.virtualvolunteer.app.ui.racedetail

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.model.RaceStatus
import com.virtualvolunteer.app.databinding.FragmentRaceDetailBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.RacePhotoProcessorFactory
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.ui.scan.BarcodeScanActivity
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Race control: timer, pre-start / finish photos, offline test tools, export.
 */
class RaceDetailFragment : Fragment() {

    private var _binding: FragmentRaceDetailBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private val collapsibleSections = RaceDetailCollapsibleSectionsController()

    private lateinit var faceDetector: MlKitFaceDetector
    private lateinit var faceEmbedder: TfliteFaceEmbedder
    private lateinit var photoProcessor: RacePhotoProcessor
    private lateinit var photoImports: RaceDetailPhotoBulkImporter
    private lateinit var offlineTestActions: RaceDetailOfflineTestActions
    private lateinit var participantActions: RaceDetailParticipantActionsCoordinator
    private lateinit var raceFlowActions: RaceDetailRaceFlowActions

    private lateinit var participantAdapter: ParticipantDashboardAdapter
    private lateinit var eventPhotosAdapter: RaceEventPhotosGridAdapter

    private var latestRace: RaceEntity? = null

    private val barcodeScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pid = participantActions.consumePendingScanParticipantId()
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
        offlineTestActions.launchSingleFinishPhotoDebug(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = requireActivity().applicationContext
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val stack = RacePhotoProcessorFactory.createStack(appContext, repo)
        faceDetector = stack.faceDetector
        faceEmbedder = stack.faceEmbedder
        photoProcessor = stack.processor
        photoImports = RaceDetailPhotoBulkImporter(
            requireActivity().application as VirtualVolunteerApp,
            raceId,
            photoProcessor,
        )
        offlineTestActions = RaceDetailOfflineTestActions(this, raceId, photoProcessor, TAG)
        participantActions = RaceDetailParticipantActionsCoordinator(
            fragment = this,
            raceId = raceId,
            runWithCameraPermission = { action -> runWithCameraPermission(action) },
            launchBarcodeScanActivity = {
                barcodeScanLauncher.launch(Intent(requireContext(), BarcodeScanActivity::class.java))
            },
        )
        raceFlowActions = RaceDetailRaceFlowActions(this, raceId)
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
            onScanCode = { id -> participantActions.openBarcodeScan(id) },
            onRemove = { id -> participantActions.confirmRemoveParticipant(id) },
            onEditDisplayName = { id, current -> participantActions.showParticipantNameEditor(id, current) },
            onOpenPhotos = { id -> participantActions.openRaceParticipantPhotos(raceId, id) },
            onFaceLookup = { id -> participantActions.openParticipantLookupForRow(id) },
        )
        binding.participantsRecycler.adapter = participantAdapter
        binding.participantsRecycler.itemAnimator = null

        eventPhotosAdapter = RaceEventPhotosGridAdapter(viewLifecycleOwner.lifecycleScope) { _, index ->
            RaceEventPhotoViewerDialogFragment.show(
                requireActivity().supportFragmentManager,
                raceId,
                eventPhotosAdapter.currentList,
                index,
            )
        }
        binding.eventPhotosRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.eventPhotosRecycler.adapter = eventPhotosAdapter
        binding.eventPhotosRecycler.itemAnimator = null

        requireActivity().supportFragmentManager.setFragmentResultListener(
            RaceEventPhotoViewerDialogFragment.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            if (bundle.getBoolean(RaceEventPhotoViewerDialogFragment.EXTRA_LIST_CHANGED, false)) {
                refreshEventPhotosGrid()
            }
        }

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
                            collapsibleSections.render(binding)
                            refreshEventPhotosGrid()
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
                                participantsExpanded = collapsibleSections.participantsExpanded,
                            )
                        }
                }
            }
        }

        binding.btnStartRace.setOnClickListener { raceFlowActions.onStartRaceClicked() }
        binding.btnPreStartPhoto.setOnClickListener { raceFlowActions.onPreStartPhotoClicked() }
        binding.btnTakeFinishPhoto.setOnClickListener { raceFlowActions.onTakeFinishPhotoClicked() }
        binding.btnFinishRace.setOnClickListener { raceFlowActions.onFinishRaceClicked() }
        binding.btnExport.setOnClickListener { raceFlowActions.onExportClicked() }
        binding.btnExportTimesCsv.setOnClickListener { raceFlowActions.onExportTimesCsvClicked() }
        binding.btnExportParticipantsCsv.setOnClickListener { raceFlowActions.onExportParticipantsCsvClicked() }

        binding.btnImportStartPhotos.setOnClickListener {
            pickMultipleStartDocuments.launch(arrayOf("image/*"))
        }
        binding.btnImportFinishPhotos.setOnClickListener {
            pickMultipleFinishDocuments.launch(arrayOf("image/*"))
        }
        binding.btnBuildTestProtocol.setOnClickListener { offlineTestActions.onBuildTestProtocolClicked() }
        binding.btnTestSingleFinishPhoto.setOnClickListener {
            pickSingleFinishDocument.launch(arrayOf("image/*"))
        }

        binding.btnAddManualFinish.setOnClickListener { participantActions.openManualFinishBottomSheet() }

        binding.btnEditRaceStartTime.setOnClickListener { showEditRaceStartTimeDialog() }

        binding.offlineTestHeaderLayout.setOnClickListener { collapsibleSections.toggleOfflineTest(binding) }
        binding.eventPhotosHeaderLayout.setOnClickListener { collapsibleSections.toggleEventPhotos(binding) }
        binding.participantsHeaderLayout.setOnClickListener { collapsibleSections.toggleParticipants(binding) }
        binding.pipelineDebugHeaderLayout.setOnClickListener { collapsibleSections.togglePipelineDebug(binding) }

        collapsibleSections.render(binding)
    }

    override fun onResume() {
        super.onResume()
        refreshEventPhotosGrid()
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
                participantsExpanded = collapsibleSections.participantsExpanded,
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

    private fun refreshEventPhotosGrid() {
        if (_binding == null || !::eventPhotosAdapter.isInitialized) return
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val paths = repo.listEventPhotoPaths(raceId)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                eventPhotosAdapter.submitList(paths)
                binding.eventPhotosEmpty.visibility = if (paths.isEmpty()) View.VISIBLE else View.GONE
                binding.eventPhotosRecycler.visibility = if (paths.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showEditRaceStartTimeDialog() {
        RaceEditStartTimeDialogHelper.show(
            requireContext(),
            lifecycleScope,
            raceId,
            latestRace,
            (requireActivity().application as VirtualVolunteerApp).raceRepository,
        )
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
}
