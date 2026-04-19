package com.virtualvolunteer.app.ui.racedetail

import android.graphics.Typeface
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
) : ListAdapter<ParticipantDashboardRow, ParticipantDashboardAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ParticipantDashboardRowBinding.inflate(inflater, parent, false)
        return VH(binding, onScanCode, onRemove, onEditDisplayName, onOpenPhotos)
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
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ParticipantDashboardRow) {
            val thumbPath = row.faceThumbnailPath
            if (!thumbPath.isNullOrBlank() && File(thumbPath).exists()) {
                val bmp = PreviewImageLoader.loadThumbnail(thumbPath, maxSidePx = 256)
                binding.participantThumb.setImageBitmap(bmp)
            } else {
                binding.participantThumb.setImageBitmap(null)
                binding.participantThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
            }

            val rank = row.finishRank
            if (rank != null) {
                binding.participantFinishRank.visibility = View.VISIBLE
                binding.participantFinishRank.text =
                    binding.root.context.getString(R.string.participant_rank_fmt, rank)
            } else {
                binding.participantFinishRank.visibility = View.GONE
            }

            val name = row.displayName?.trim()?.takeIf { it.isNotEmpty() }
            binding.participantHeadline.text = name
                ?: binding.root.context.getString(R.string.participant_tap_to_name)

            val digest = if (row.embeddingFailed) {
                binding.root.context.getString(R.string.participant_embedding_failed)
            } else {
                val embShort = if (row.embedding.length > 48) {
                    row.embedding.take(48) + "…"
                } else {
                    row.embedding
                }
                embShort
            }
            binding.participantEmbeddingDigest.text = digest

            val info = row.registryInfo
            if (!info.isNullOrBlank()) {
                binding.participantRegistryInfo.visibility = View.VISIBLE
                binding.participantRegistryInfo.text = info
            } else {
                binding.participantRegistryInfo.visibility = View.GONE
            }

            val startMs = row.raceStartedAtEpochMillis
            val finishMs = row.finishTimeEpochMillis
            if (startMs != null && finishMs != null) {
                binding.participantMovingTime.visibility = View.VISIBLE
                val delta = (finishMs - startMs).coerceAtLeast(0L)
                binding.participantMovingTime.text = binding.root.context.getString(
                    R.string.participant_moving_time_fmt,
                    RaceUiFormatter.formatElapsed(delta),
                )
                binding.participantMovingTime.setTypeface(binding.participantMovingTime.typeface, Typeface.BOLD)
            } else {
                binding.participantMovingTime.visibility = View.GONE
            }

            val ctx = binding.root.context
            if (finishMs != null) {
                binding.participantFinishTime.text = ctx.getString(
                    R.string.participant_finish_fmt,
                    RaceUiFormatter.formatDateTime(finishMs),
                )
                binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.accent_pink))
                binding.root.alpha = 1f
            } else {
                binding.participantFinishTime.text = ctx.getString(
                    R.string.participant_no_finish_yet,
                )
                binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                binding.root.alpha = 0.92f
            }

            val scan = row.scannedPayload
            if (!scan.isNullOrBlank()) {
                binding.participantScannedPayload.visibility = View.VISIBLE
                binding.participantScannedPayload.text = binding.root.context.getString(
                    R.string.participant_scan_fmt,
                    scan,
                )
            } else {
                binding.participantScannedPayload.visibility = View.GONE
            }

            binding.participantThumb.isClickable = true
            binding.participantThumb.setOnClickListener { onOpenPhotos(row.participantId) }

            binding.participantHeadline.setOnClickListener {
                onEditDisplayName(row.participantId, row.displayName)
            }
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
