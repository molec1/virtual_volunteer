package com.virtualvolunteer.app.ui.racedetail

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
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
import com.google.mlkit.vision.face.Face
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.ParticipantDashboardDbRow
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.databinding.BottomSheetParticipantLookupBinding
import com.virtualvolunteer.app.databinding.ItemParticipantLookupResultBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.FaceCropBounds
import com.virtualvolunteer.app.domain.face.FaceThumbnailSaver
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.matching.ParticipantEmbeddingSet
import com.virtualvolunteer.app.domain.participants.RoomRaceParticipantPool
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bottom sheet to lookup a participant by face embedding from a selected photo.
 */
class ParticipantLookupBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetParticipantLookupBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

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
                    val face = detected.first() // Take the first face found
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
                        performLookup(embedding!!)
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

        lookupAdapter = ParticipantLookupAdapter(requireContext(), viewLifecycleOwner.lifecycleScope) {
            // On participant selected for manual link
            val pId = it.participantId
            val embedding = selectedEmbedding
            val photoPath = selectedPhotoPath
            if (embedding == null || photoPath == null) {
                Toast.makeText(requireContext(), R.string.participant_lookup_error_generic, Toast.LENGTH_SHORT).show()
                return@ParticipantLookupAdapter
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
                val outcome = repo.recordManualFinishDetection(
                    raceId = raceId,
                    participantId = pId,
                    finishTimeEpochMillis = System.currentTimeMillis(), // Use current time for manual link
                    sourcePhotoPath = photoPath,
                    sourceEmbedding = embedding,
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.participant_lookup_success, it.displayName ?: it.participantId.toString()),
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                }
            }
        }
        binding.lookupResultsRecycler.adapter = lookupAdapter
        binding.lookupResultsRecycler.layoutManager = LinearLayoutManager(requireContext())

        binding.btnSelectLookupPhoto.setOnClickListener { pickSingleLookupPhoto.launch(arrayOf("image/*")) }

        updateUi()
    }

    private fun updateUi() {
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

    private suspend fun performLookup(embedding: FloatArray) {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val baseSets = repo.listParticipantEmbeddingSets(raceId).filter { it.hasEmbeddings }
        val results = photoProcessor.matcher.nearestN(embedding, baseSets, 10)
            .filter { it.cosineSimilarity >= photoProcessor.matcher.threshold() }
            .map { match: FaceMatchEngine.MatchCandidate ->
                val p = repo.getParticipantHashById(match.candidate.participant.id)!!
                ParticipantLookupResult(
                    participantId = p.id,
                    displayName = p.displayName,
                    scannedPayload = p.scannedPayload,
                    primaryThumbnailPhotoPath = p.primaryThumbnailPhotoPath,
                    cosineSimilarity = match.cosineSimilarity,
                )
            }
        withContext(Dispatchers.Main) {
            if (results.isEmpty()) {
                Toast.makeText(requireContext(), R.string.participant_lookup_no_match, Toast.LENGTH_SHORT).show()
                lookupAdapter.submitList(emptyList())
            } else {
                lookupAdapter.submitList(results)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RACE_ID = "raceId"
        private const val TAG = "ParticipantLookup"

        fun newInstance(raceId: String): ParticipantLookupBottomSheet = ParticipantLookupBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_RACE_ID, raceId)
            }
        }
    }
}

private data class ParticipantLookupResult(
    val participantId: Long,
    val displayName: String?,
    val scannedPayload: String?,
    val primaryThumbnailPhotoPath: String?,
    val cosineSimilarity: Float,
)

private class ParticipantLookupAdapter(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onClick: (ParticipantLookupResult) -> Unit,
) : ListAdapter<ParticipantLookupResult, ParticipantLookupAdapter.VH>(
    object : DiffUtil.ItemCallback<ParticipantLookupResult>() {
        override fun areItemsTheSame(oldItem: ParticipantLookupResult, newItem: ParticipantLookupResult): Boolean {
            return oldItem.participantId == newItem.participantId
        }

        override fun areContentsTheSame(oldItem: ParticipantLookupResult, newItem: ParticipantLookupResult): Boolean {
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
        private val lifecycleScope: LifecycleCoroutineScope
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: ParticipantLookupResult) {
            binding.root.setOnClickListener { onClick(result) }
            binding.participantLookupName.text = buildString {
                append(result.displayName ?: "#${result.participantId}")
                append(" (ID ")
                append(result.participantId)
                append(")")
            }
            binding.participantLookupScanCode.text = result.scannedPayload?.let { "Scan: $it" } ?: binding.root.context.getString(R.string.identity_registry_no_scan)
            binding.participantLookupCosineSimilarity.text = "Similarity: %.2f".format(result.cosineSimilarity)
            if (!result.primaryThumbnailPhotoPath.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.Default) {
                    val bmp = PreviewImageLoader.loadThumbnailOriented(result.primaryThumbnailPhotoPath, maxSidePx = 180)
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