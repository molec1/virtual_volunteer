package com.virtualvolunteer.app.ui.racedetail

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.databinding.BottomSheetParticipantLookupBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RoomRaceParticipantPool
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Face lookup: ranks **all scanned codes on this device** by max cosine vs the query (photo embedding
 * or every stored vector on a dashboard row). Choosing a row **assigns** that scan and global identity
 * to the participant (same as scanning the code), including duplicate-scan merges in this race.
 */
class ParticipantLookupBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetParticipantLookupBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    /** When set, compares this protocol row's embedding to others and merges this row into the chosen keeper. */
    private val donorParticipantId: Long?
        get() = if (requireArguments().containsKey(ARG_DONOR_PARTICIPANT_ID)) {
            requireArguments().getLong(ARG_DONOR_PARTICIPANT_ID)
        } else {
            null
        }

    private lateinit var photoProcessor: RacePhotoProcessor
    private lateinit var lookupAdapter: ParticipantLookupAdapter

    private var selectedPhotoPath: String? = null
    private var selectedEmbedding: FloatArray? = null

    private val pickSingleLookupPhoto = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val tmp = File(ctx.cacheDir, "lookup_photo_${System.currentTimeMillis()}.img")
            try {
                UriFileCopy.copyToFile(ctx, uri, tmp)
                when (val extraction = extractLookupEmbeddingFromTemp(photoProcessor, tmp)) {
                    LookupPhotoEmbeddingResult.DecodeFailed -> {
                        // Same as original: no dedicated toast; UI updates with null embedding.
                        withContext(Dispatchers.Main) {
                            selectedPhotoPath = tmp.absolutePath
                            selectedEmbedding = null
                            updateUi()
                        }
                    }
                    LookupPhotoEmbeddingResult.NoFaces -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.participant_lookup_no_faces, Toast.LENGTH_SHORT).show()
                        }
                    }
                    LookupPhotoEmbeddingResult.EmbedFailed -> {
                        withContext(Dispatchers.Main) {
                            selectedPhotoPath = tmp.absolutePath
                            selectedEmbedding = null
                            updateUi()
                        }
                    }
                    is LookupPhotoEmbeddingResult.Ok -> {
                        val embedding = extraction.embedding
                        withContext(Dispatchers.Main) {
                            selectedPhotoPath = tmp.absolutePath
                            selectedEmbedding = embedding
                            updateUi()
                            performLookup(listOf(embedding), excludeParticipantIdForHistoricalPool = null)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "lookup photo pick failed", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        photoProcessor = RacePhotoProcessor(
            races = repo,
            faces = MlKitFaceDetector(),
            embedder = TfliteFaceEmbedder(requireActivity().applicationContext),
            matcher = FaceMatchEngine(),
            pool = RoomRaceParticipantPool(repo),
            appContext = requireActivity().applicationContext,
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetParticipantLookupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val donor = donorParticipantId
        if (donor != null) {
            binding.lookupSheetTitle.setText(R.string.participant_lookup_reassign_title)
            binding.btnSelectLookupPhoto.visibility = View.GONE
            binding.lookupPhotoPreview.visibility = View.GONE
            binding.lookupPhotoEmptyHint.visibility = View.GONE
            lifecycleScope.launch(Dispatchers.IO) {
                val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
                val probes = probeEmbeddingsForParticipant(repo, raceId, donor)
                withContext(Dispatchers.Main) {
                    if (probes.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.participant_lookup_no_embedding, Toast.LENGTH_LONG).show()
                        dismiss()
                    } else {
                        performLookup(probes, excludeParticipantIdForHistoricalPool = donor)
                    }
                }
            }
        } else {
            binding.lookupSheetTitle.setText(R.string.participant_lookup_sheet_title)
            binding.btnSelectLookupPhoto.visibility = View.VISIBLE
            binding.lookupPhotoPreview.visibility = View.VISIBLE
            binding.lookupPhotoEmptyHint.visibility = View.VISIBLE
        }

        lookupAdapter = ParticipantLookupAdapter(requireContext(), viewLifecycleOwner.lifecycleScope) { result ->
            val embedding = selectedEmbedding
            val donorId = donorParticipantId
            if (donorId != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
                    try {
                        repo.assignParticipantToScannedIdentityFromFaceLookup(
                            raceId = raceId,
                            participantId = donorId,
                            scanCodeTrimmed = result.scanCodeTrimmed,
                            registryIds = result.registryIds,
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                R.string.participant_lookup_assign_like_scan_done,
                                Toast.LENGTH_LONG,
                            ).show()
                            dismiss()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "assign identity from lookup failed", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                R.string.participant_lookup_error_generic,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            } else {
                if (embedding == null) {
                    Toast.makeText(requireContext(), R.string.participant_lookup_error_generic, Toast.LENGTH_SHORT).show()
                    return@ParticipantLookupAdapter
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.participant_lookup_photo_mode_hint, result.scanCodeTrimmed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        binding.lookupResultsRecycler.adapter = lookupAdapter
        binding.lookupResultsRecycler.layoutManager = LinearLayoutManager(requireContext())

        binding.btnSelectLookupPhoto.setOnClickListener { pickSingleLookupPhoto.launch(arrayOf("image/*")) }

        updateUi()
    }

    private fun updateUi() {
        if (donorParticipantId != null) return
        if (selectedPhotoPath.isNullOrBlank()) {
            binding.lookupPhotoPreview.setImageBitmap(null)
            binding.lookupPhotoPreview.setBackgroundResource(R.drawable.bg_placeholder_photo)
            binding.lookupPhotoEmptyHint.visibility = View.VISIBLE
        } else {
            binding.lookupPhotoEmptyHint.visibility = View.GONE
            lifecycleScope.launch(Dispatchers.Default) {
                val bmp = PreviewImageLoader.loadThumbnailOriented(selectedPhotoPath!!, maxSidePx = 1080)
                withContext(Dispatchers.Main) {
                    binding.lookupPhotoPreview.background = null
                    binding.lookupPhotoPreview.setImageBitmap(bmp)
                }
            }
        }
    }

    private suspend fun performLookup(
        queryEmbeddings: List<FloatArray>,
        excludeParticipantIdForHistoricalPool: Long?,
    ) {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val ranked = repo.rankScannedOnDeviceIdentitiesByCosine(
            queryEmbeddings = queryEmbeddings,
            excludeParticipantIdForHistoricalPool = excludeParticipantIdForHistoricalPool,
        )
        withContext(Dispatchers.Main) {
            if (ranked.isEmpty()) {
                Toast.makeText(requireContext(), R.string.participant_lookup_no_match, Toast.LENGTH_SHORT).show()
                lookupAdapter.submitList(emptyList())
            } else {
                lookupAdapter.submitList(ranked)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RACE_ID = "raceId"
        private const val ARG_DONOR_PARTICIPANT_ID = "donorParticipantId"
        private const val TAG = "ParticipantLookup"

        fun newInstance(raceId: String, donorParticipantId: Long? = null): ParticipantLookupBottomSheet =
            ParticipantLookupBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RACE_ID, raceId)
                    if (donorParticipantId != null) {
                        putLong(ARG_DONOR_PARTICIPANT_ID, donorParticipantId)
                    }
                }
            }
    }
}
