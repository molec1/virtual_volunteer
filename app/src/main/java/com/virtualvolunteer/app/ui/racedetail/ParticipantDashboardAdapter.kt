package com.virtualvolunteer.app.ui.racedetail

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
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
class ParticipantDashboardAdapter :
    ListAdapter<ParticipantDashboardRow, ParticipantDashboardAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ParticipantDashboardRowBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ParticipantDashboardRowBinding,
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

            val descriptorLine = if (row.embeddingFailed) {
                binding.root.context.getString(R.string.participant_embedding_failed)
            } else {
                val embShort = if (row.embedding.length > 48) {
                    row.embedding.take(48) + "…"
                } else {
                    row.embedding
                }
                embShort
            }
            binding.participantHashShort.text = descriptorLine

            binding.participantDetectedTime.text =
                "Detected: ${RaceUiFormatter.formatDateTime(row.createdAtEpochMillis)}"

            val finishMs = row.finishTimeEpochMillis
            if (finishMs != null) {
                binding.participantFinishTime.text =
                    "Finish: ${RaceUiFormatter.formatDateTime(finishMs)}"
                binding.participantFinishTime.setTextColor(Color.BLACK)
                binding.root.alpha = 1f
            } else {
                binding.participantFinishTime.text = binding.root.context.getString(
                    R.string.participant_no_finish_yet,
                )
                binding.participantFinishTime.setTextColor(0xFF666666.toInt())
                binding.root.alpha = 0.92f
            }
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
