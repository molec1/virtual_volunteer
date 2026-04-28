package com.virtualvolunteer.app.ui.racedetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.data.local.RaceParticipantHashEntity
import com.virtualvolunteer.app.data.repository.ParticipantEmbeddingPreviewRow
import com.virtualvolunteer.app.data.repository.ParticipantRaceSummary
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.databinding.FragmentParticipantDetailBinding
import com.virtualvolunteer.app.databinding.ItemParticipantDetailRaceRowBinding
import com.virtualvolunteer.app.databinding.ItemParticipantEmbeddingPreviewBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays details for a single participant across all their races.
 */
class ParticipantDetailFragment : Fragment() {

    private var _binding: FragmentParticipantDetailBinding? = null
    private val binding get() = _binding!!

    private val participantId: Long
        get() = requireArguments().getLong(ARG_PARTICIPANT_ID)

    private lateinit var raceAdapter: ParticipantRaceAdapter
    private lateinit var embeddingAdapter: ParticipantEmbeddingPreviewAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentParticipantDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as VirtualVolunteerApp
        val repo = app.raceRepository

        embeddingAdapter = ParticipantEmbeddingPreviewAdapter(viewLifecycleOwner.lifecycleScope)
        binding.participantEmbeddingsRecycler.adapter = embeddingAdapter
        binding.participantEmbeddingsRecycler.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false,
        )

        raceAdapter = ParticipantRaceAdapter(viewLifecycleOwner.lifecycleScope) { raceId, hashIdForRace ->
            RaceParticipantPhotosBottomSheet.newInstance(raceId, hashIdForRace)
                .show(childFragmentManager, "raceParticipantPhotos")
        }
        binding.participantRacesRecycler.adapter = raceAdapter
        binding.participantRacesRecycler.layoutManager = LinearLayoutManager(requireContext())

        binding.btnRemoveFaceData.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                repo.removeAllFaceEmbeddingsForParticipantIdentity(participantId)
                withContext(Dispatchers.Main) {
                    refreshParticipantDetail(repo)
                }
            }
        }

        binding.btnDeleteDeviceIdentity.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val regId = withContext(Dispatchers.IO) {
                    repo.getParticipantHashById(participantId)?.identityRegistryId
                } ?: return@launch
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.participant_detail_delete_identity_title)
                    .setMessage(R.string.participant_detail_delete_identity_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.participant_detail_delete_identity_confirm) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            repo.deleteIdentityRegistryUnlinkKeepProtocol(regId)
                            withContext(Dispatchers.Main) {
                                findNavController().popBackStack()
                            }
                        }
                    }
                    .show()
            }
        }

        refreshParticipantDetail(repo)
    }

    private fun refreshParticipantDetail(repo: RaceRepository) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val participant = repo.getParticipantHashById(participantId)
            val races = repo.listRacesForParticipant(participantId)
            val embeddings = repo.listParticipantEmbeddingPreviews(participantId)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (participant != null) {
                    bindParticipantHeader(participant)
                    binding.btnDeleteDeviceIdentity.visibility =
                        if (participant.identityRegistryId != null) View.VISIBLE else View.GONE
                }
                embeddingAdapter.submitList(embeddings)
                val emptyEmb = embeddings.isEmpty()
                binding.participantDetailNoEmbeddings.visibility = if (emptyEmb) View.VISIBLE else View.GONE
                binding.participantEmbeddingsRecycler.visibility = if (emptyEmb) View.GONE else View.VISIBLE
                raceAdapter.submitList(races)
            }
        }
    }

    private fun bindParticipantHeader(participant: RaceParticipantHashEntity) {
        binding.participantDetailName.text = participant.displayName ?: "#${participant.id}"
        binding.participantDetailId.text = getString(R.string.participant_detail_protocol_id, participant.id)
        binding.participantDetailScanCode.text =
            participant.scannedPayload?.let { "Scan: $it" } ?: getString(R.string.identity_registry_no_scan)

        val thumbPath = sequenceOf(
            participant.primaryThumbnailPhotoPath,
            participant.faceThumbnailPath,
        ).firstOrNull { !it.isNullOrBlank() }
        if (!thumbPath.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                val bmp = PreviewImageLoader.loadThumbnailOrientedInset(thumbPath, maxSidePx = 240)
                withContext(Dispatchers.Main) {
                    if (_binding != null) binding.participantDetailThumbnail.setImageBitmap(bmp)
                }
            }
        } else {
            binding.participantDetailThumbnail.setImageResource(R.drawable.ic_person)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PARTICIPANT_ID = "participantId"

        fun newInstance(participantId: Long): ParticipantDetailFragment = ParticipantDetailFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_PARTICIPANT_ID, participantId)
            }
        }
    }
}

private class ParticipantEmbeddingPreviewAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
) : ListAdapter<ParticipantEmbeddingPreviewRow, ParticipantEmbeddingPreviewAdapter.VH>(
    object : DiffUtil.ItemCallback<ParticipantEmbeddingPreviewRow>() {
        override fun areItemsTheSame(
            oldItem: ParticipantEmbeddingPreviewRow,
            newItem: ParticipantEmbeddingPreviewRow,
        ): Boolean = oldItem.embeddingId == newItem.embeddingId

        override fun areContentsTheSame(
            oldItem: ParticipantEmbeddingPreviewRow,
            newItem: ParticipantEmbeddingPreviewRow,
        ): Boolean = oldItem == newItem
    },
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemParticipantEmbeddingPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, lifecycleScope)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemParticipantEmbeddingPreviewBinding,
        private val lifecycleScope: LifecycleCoroutineScope,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ParticipantEmbeddingPreviewRow) {
            val ctx = binding.root.context
            val sourceLabel = ctx.getString(
                when (row.sourceType) {
                    EmbeddingSourceType.START -> R.string.embedding_source_start
                    EmbeddingSourceType.FINISH_AUTO -> R.string.embedding_source_finish_auto
                    EmbeddingSourceType.FINISH_MANUAL_LINK -> R.string.embedding_source_finish_manual_link
                },
            )
            binding.embeddingPreviewCaption.text = ctx.getString(
                R.string.participant_embedding_preview_caption,
                sourceLabel,
                row.raceLabelShort,
            )
            val path = row.previewPhotoPath
            if (!path.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.Default) {
                    val bmp = PreviewImageLoader.loadThumbnailOrientedInset(path, maxSidePx = 176)
                    withContext(Dispatchers.Main) {
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            binding.embeddingPreviewImage.setImageBitmap(bmp)
                        }
                    }
                }
            } else {
                binding.embeddingPreviewImage.setImageResource(R.drawable.ic_person)
            }
        }
    }
}

private class ParticipantRaceAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onClick: (raceId: String, participantHashId: Long) -> Unit,
) : ListAdapter<ParticipantRaceSummary, ParticipantRaceAdapter.VH>(
    object : DiffUtil.ItemCallback<ParticipantRaceSummary>() {
        override fun areItemsTheSame(oldItem: ParticipantRaceSummary, newItem: ParticipantRaceSummary): Boolean {
            return oldItem.raceId == newItem.raceId
        }

        override fun areContentsTheSame(oldItem: ParticipantRaceSummary, newItem: ParticipantRaceSummary): Boolean {
            return oldItem == newItem
        }
    },
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemParticipantDetailRaceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, lifecycleScope)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(
        private val binding: ItemParticipantDetailRaceRowBinding,
        private val lifecycleScope: LifecycleCoroutineScope,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: ParticipantRaceSummary) {
            binding.root.setOnClickListener {
                onClick(summary.raceId, summary.participantHashId)
            }
            binding.raceTitle.text = buildString {
                append("Race ")
                append(summary.raceId.take(8))
                append("… (")
                append(RaceUiFormatter.formatDate(summary.raceCreatedAtMillis))
                append(")")
            }
            binding.raceFinishTime.text = summary.protocolFinishTimeEpochMillis?.let {
                binding.root.context.getString(R.string.participant_finish_fmt, RaceUiFormatter.formatTime(it)) + " " +
                    summary.finishRank?.let { " (#$it)" }.orEmpty()
            } ?: binding.root.context.getString(R.string.participant_no_finish_yet)

            binding.raceMovingTime.text = summary.raceStartedAtEpochMillis?.let { started ->
                summary.protocolFinishTimeEpochMillis?.let { finished ->
                    val elapsed = (finished - started).coerceAtLeast(0L)
                    binding.root.context.getString(R.string.participant_moving_time_fmt, RaceUiFormatter.formatElapsed(elapsed))
                }
            } ?: ""

            if (!summary.raceThumbnailPhotoPath.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.Default) {
                    val bmp = PreviewImageLoader.loadThumbnailOrientedInset(summary.raceThumbnailPhotoPath, maxSidePx = 120)
                    withContext(Dispatchers.Main) {
                        binding.raceThumbnail.setImageBitmap(bmp)
                    }
                }
            } else {
                binding.raceThumbnail.setImageResource(R.drawable.ic_photo_placeholder)
            }
        }
    }
}
