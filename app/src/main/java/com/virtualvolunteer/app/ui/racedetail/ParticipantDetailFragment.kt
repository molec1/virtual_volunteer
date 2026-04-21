package com.virtualvolunteer.app.ui.racedetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.databinding.FragmentParticipantDetailBinding
import com.virtualvolunteer.app.databinding.ItemParticipantDetailRaceRowBinding
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentParticipantDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as VirtualVolunteerApp
        val repo = app.raceRepository

        raceAdapter = ParticipantRaceAdapter(viewLifecycleOwner.lifecycleScope) { raceId, hashIdForRace ->
            RaceParticipantPhotosBottomSheet.newInstance(raceId, hashIdForRace)
                .show(childFragmentManager, "raceParticipantPhotos")
        }
        binding.participantRacesRecycler.adapter = raceAdapter
        binding.participantRacesRecycler.layoutManager = LinearLayoutManager(requireContext())

        lifecycleScope.launch(Dispatchers.IO) {
            val participant = repo.getParticipantHashById(participantId)
            val races = repo.listRacesForParticipant(participantId)
            withContext(Dispatchers.Main) {
                if (participant != null) {
                    binding.participantDetailName.text = participant.displayName ?: "#${participant.id}"
                    binding.participantDetailId.text = getString(R.string.participant_detail_protocol_id, participant.id)
                    binding.participantDetailScanCode.text = participant.scannedPayload?.let { "Scan: $it" } ?: getString(R.string.identity_registry_no_scan)

                    val thumbPath = sequenceOf(
                        participant.primaryThumbnailPhotoPath,
                        participant.faceThumbnailPath,
                    ).firstOrNull { !it.isNullOrBlank() }
                    if (!thumbPath.isNullOrBlank()) {
                        lifecycleScope.launch(Dispatchers.Default) {
                            val bmp = PreviewImageLoader.loadThumbnailOriented(thumbPath, maxSidePx = 240)
                            withContext(Dispatchers.Main) {
                                binding.participantDetailThumbnail.setImageBitmap(bmp)
                            }
                        }
                    } else {
                        binding.participantDetailThumbnail.setImageResource(R.drawable.ic_person)
                    }
                }
                raceAdapter.submitList(races)
            }
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

private class ParticipantRaceAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onClick: (raceId: String, participantHashId: Long) -> Unit,
) : ListAdapter<RaceRepository.ParticipantRaceSummary, ParticipantRaceAdapter.VH>(
    object : DiffUtil.ItemCallback<RaceRepository.ParticipantRaceSummary>() {
        override fun areItemsTheSame(oldItem: RaceRepository.ParticipantRaceSummary, newItem: RaceRepository.ParticipantRaceSummary): Boolean {
            return oldItem.raceId == newItem.raceId
        }

        override fun areContentsTheSame(oldItem: RaceRepository.ParticipantRaceSummary, newItem: RaceRepository.ParticipantRaceSummary): Boolean {
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
        private val lifecycleScope: LifecycleCoroutineScope
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: RaceRepository.ParticipantRaceSummary) {
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
                    summary.finishRank?.let { " (#$it)" } .orEmpty()
            } ?: binding.root.context.getString(R.string.participant_no_finish_yet)

            binding.raceMovingTime.text = summary.raceStartedAtEpochMillis?.let { started ->
                summary.protocolFinishTimeEpochMillis?.let { finished ->
                    val elapsed = (finished - started).coerceAtLeast(0L)
                    binding.root.context.getString(R.string.participant_moving_time_fmt, RaceUiFormatter.formatElapsed(elapsed))
                }
            } ?: ""

            if (!summary.raceThumbnailPhotoPath.isNullOrBlank()) {
                lifecycleScope.launch(Dispatchers.Default) {
                    val bmp = PreviewImageLoader.loadThumbnailOriented(summary.raceThumbnailPhotoPath, maxSidePx = 120)
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