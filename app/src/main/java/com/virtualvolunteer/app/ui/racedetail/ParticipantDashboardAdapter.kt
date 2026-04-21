package com.virtualvolunteer.app.ui.racedetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow
import com.virtualvolunteer.app.databinding.ParticipantDashboardRowBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import java.io.File

/**
 * Participant / protocol rows for the race dashboard (race-local pool + finish join).
 */
class ParticipantDashboardAdapter(
    private val onScanCode: (participantId: Long) -> Unit,
    private val onRemove: (participantId: Long) -> Unit,
    private val onEditDisplayName: (participantId: Long, currentName: String?) -> Unit,
    private val onOpenPhotos: (participantId: Long) -> Unit,
    private val onFaceLookup: (participantId: Long) -> Unit,
) : ListAdapter<ParticipantDashboardRow, ParticipantDashboardAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ParticipantDashboardRowBinding.inflate(inflater, parent, false)
        return VH(binding, onScanCode, onRemove, onEditDisplayName, onOpenPhotos, onFaceLookup)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ParticipantDashboardRowBinding,
        private val onScanCode: (Long) -> Unit,
        private val onRemove: (Long) -> Unit,
        private val onEditDisplayName: (Long, String?) -> Unit,
        private val onOpenPhotos: (Long) -> Unit,
        private val onFaceLookup: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ParticipantDashboardRow) {
            val thumbPath = sequenceOf(row.primaryThumbnailPhotoPath, row.faceThumbnailPath)
                .firstOrNull { !it.isNullOrBlank() && File(it).exists() }
            if (!thumbPath.isNullOrBlank()) {
                val bmp = PreviewImageLoader.loadThumbnailOrientedInset(
                    thumbPath,
                    maxSidePx = 256,
                    edgeInsetFraction = 0.022f,
                )
                binding.participantThumb.setImageBitmap(bmp)
                if (bmp != null) {
                    binding.participantThumb.background = null
                } else {
                    binding.participantThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
                }
            } else {
                binding.participantThumb.setImageBitmap(null)
                binding.participantThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
            }

            val rank = row.finishRank
            if (rank != null) {
                binding.participantRank.visibility = View.VISIBLE
                binding.participantRank.text =
                    binding.root.context.getString(R.string.participant_rank_fmt, rank)
            } else {
                binding.participantRank.visibility = View.GONE
            }

            val name = row.displayName?.trim()?.takeIf { it.isNotEmpty() }
            binding.participantName.text = name
                ?: binding.root.context.getString(R.string.participant_tap_to_name)


            val info = row.registryInfo
            if (!info.isNullOrBlank()) {
                binding.participantRegistryInfo.visibility = View.VISIBLE
                binding.participantRegistryInfo.text = info
            } else {
                binding.participantRegistryInfo.visibility = View.GONE
            }

            val startMs = row.raceStartedAtEpochMillis
            val finishMs = row.finishTimeEpochMillis
            binding.participantMovingTime.visibility = View.VISIBLE
            val ctx = binding.root.context
            if (startMs != null && finishMs != null) {
                val delta = (finishMs - startMs).coerceAtLeast(0L)
                binding.participantMovingTime.text = RaceUiFormatter.formatElapsed(delta)
                binding.participantMovingTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            } else {
                binding.participantMovingTime.text = ctx.getString(R.string.participant_dashboard_time_dash)
                binding.participantMovingTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            if (finishMs != null) {
                binding.participantFinishTime.text = ctx.getString(
                    R.string.participant_finish_fmt,
                    RaceUiFormatter.formatDateTime(finishMs),
                )
                binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            } else {
                binding.participantFinishTime.text = ctx.getString(R.string.participant_no_finish_yet)
                binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            val scan = row.scannedPayload
            if (!scan.isNullOrBlank()) {
                binding.participantScanCode.visibility = View.VISIBLE
                binding.participantScanCode.text = binding.root.context.getString(
                    R.string.participant_scan_fmt,
                    scan,
                )
            } else {
                binding.participantScanCode.visibility = View.GONE
            }

            binding.participantThumb.isClickable = true
            binding.participantThumb.setOnClickListener { onOpenPhotos(row.participantId) }

            binding.participantName.setOnClickListener {
                onEditDisplayName(row.participantId, row.displayName)
            }
            binding.btnFaceLookup.setOnClickListener { onFaceLookup(row.participantId) }
            binding.btnScanCode.setOnClickListener { onScanCode(row.participantId) }
            binding.btnRemoveParticipant.setOnClickListener { onRemove(row.participantId) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ParticipantDashboardRow>() {
            override fun areItemsTheSame(
                oldItem: ParticipantDashboardRow,
                newItem: ParticipantDashboardRow,
            ): Boolean = oldItem.participantId == newItem.participantId

            override fun areContentsTheSame(
                oldItem: ParticipantDashboardRow,
                newItem: ParticipantDashboardRow,
            ): Boolean = oldItem == newItem
        }
    }
}
