package com.virtualvolunteer.app.ui.racedetail

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.databinding.BottomSheetParticipantLookupBinding
import com.virtualvolunteer.app.databinding.ItemParticipantLookupResultBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.data.repository.ScannedIdentityLookupRank
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
            var bmp: Bitmap? = null
            var embedding: FloatArray? = null
            try {
                UriFileCopy.copyToFile(ctx, uri, tmp)
                bmp = photoProcessor.loadVisionBitmap(tmp)
                if (bmp != null) {
                    val detected = photoProcessor.faces.detectFaces(bmp)
                    if (detected.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), R.string.participant_lookup_no_faces, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val face = detected.first()
                    val crop = photoProcessor.cropFace(bmp, face)
                    if (crop != null) {
                        val embedResult = runCatching { photoProcessor.embedder.embed(crop) }
                        embedResult.onSuccess { embedding = it }
                        crop.recycle()
                    }
                }
                withContext(Dispatchers.Main) {
                    selectedPhotoPath = tmp.absolutePath
                    selectedEmbedding = embedding
                    updateUi()
                    if (embedding != null) {
                        performLookup(listOf(embedding!!), excludeParticipantIdForHistoricalPool = null)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "lookup photo pick failed", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                bmp?.recycle()
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
                val probes = probeEmbeddingsForParticipant(raceId, donor)
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

    /** All usable embedding vectors for this race participant (multi-vector → list for max-likelihood lookup). */
    private suspend fun probeEmbeddingsForParticipant(raceId: String, participantId: Long): List<FloatArray> {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val sets = repo.listParticipantEmbeddingSets(raceId)
        val set = sets.find { it.participant.id == participantId } ?: return emptyList()
        val fromTable = set.embeddingStrings.mapNotNull { str ->
            EmbeddingMath.parseCommaSeparated(str).takeIf { it.isNotEmpty() }
        }
        if (fromTable.isNotEmpty()) return fromTable
        val row = repo.getParticipantHashById(participantId) ?: return emptyList()
        if (!row.embeddingFailed && row.embedding.isNotBlank()) {
            val legacy = EmbeddingMath.parseCommaSeparated(row.embedding)
            if (legacy.isNotEmpty()) return listOf(legacy)
        }
        return emptyList()
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

private class ParticipantLookupAdapter(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onClick: (ScannedIdentityLookupRank) -> Unit,
) : ListAdapter<ScannedIdentityLookupRank, ParticipantLookupAdapter.VH>(
    object : DiffUtil.ItemCallback<ScannedIdentityLookupRank>() {
        override fun areItemsTheSame(oldItem: ScannedIdentityLookupRank, newItem: ScannedIdentityLookupRank): Boolean {
            return oldItem.scanCodeTrimmed == newItem.scanCodeTrimmed
        }

        override fun areContentsTheSame(oldItem: ScannedIdentityLookupRank, newItem: ScannedIdentityLookupRank): Boolean {
            return oldItem == newItem
        }
    },
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemParticipantLookupResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, lifecycleScope)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(
        private val binding: ItemParticipantLookupResultBinding,
        private val lifecycleScope: LifecycleCoroutineScope,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: ScannedIdentityLookupRank) {
            binding.root.setOnClickListener { onClick(result) }
            binding.participantLookupName.text =
                context.getString(R.string.participant_scan_fmt, result.scanCodeTrimmed)
            val notes = result.notes?.trim()?.takeIf { it.isNotEmpty() }
            if (notes != null) {
                binding.participantLookupScanCode.visibility = View.VISIBLE
                binding.participantLookupScanCode.text = notes
            } else {
                binding.participantLookupScanCode.visibility = View.GONE
            }
            binding.participantLookupCosineSimilarity.text =
                context.getString(R.string.participant_lookup_similarity_fmt, result.maxCosineSimilarity)
            val thumb = result.registryThumbnailPath?.takeIf { File(it).exists() }
            if (!thumb.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.Default) {
                    val bmp = PreviewImageLoader.loadThumbnailOrientedInset(thumb, maxSidePx = 180)
                    withContext(Dispatchers.Main) {
                        binding.participantLookupThumbnail.setImageBitmap(bmp)
                    }
                }
            } else {
                binding.participantLookupThumbnail.setImageResource(R.drawable.ic_person)
            }
        }
    }
}
