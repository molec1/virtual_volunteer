package com.virtualvolunteer.app.ui.racedetail

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow
import com.virtualvolunteer.app.databinding.ParticipantDashboardRowBinding
import com.virtualvolunteer.app.ui.util.CardShadowStyler
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import java.io.File

/** Short human-readable label for a participant row: scan code or fallback ID. */
internal fun ParticipantDashboardRow.displayLabel(): String =
    scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
        ?: "#$participantId"

/**
 * Participant / protocol rows for the race dashboard (race-local pool + finish join).
 *
 * Supports long-press drag-to-merge: the dragged row is dimmed and the current drop target
 * receives a coloured stroke. Visual state is updated via [setDragPositions]; actual merge
 * is triggered by [onMergeRequest] once the drag ends over a different row.
 */
class ParticipantDashboardAdapter(
    private val onScanCode: (participantId: Long) -> Unit,
    private val onRemove: (participantId: Long) -> Unit,
    private val onOpenPhotos: (participantId: Long) -> Unit,
    private val onFaceLookup: (participantId: Long) -> Unit,
) : ListAdapter<ParticipantDashboardRow, ParticipantDashboardAdapter.VH>(DIFF) {

    /** Position of the row currently being dragged (-1 when idle). */
    var dragSourcePosition: Int = -1
        private set

    /** Position of the current drop target row (-1 when none). */
    var dropTargetPosition: Int = -1
        private set

    /** Public accessor for protected [getItem] — used by [ParticipantMergeDragCallback]. */
    fun getItemAt(position: Int): ParticipantDashboardRow? =
        if (position in 0 until itemCount) getItem(position) else null

    /**
     * Updates drag/drop highlight positions and notifies only the affected rows so thumbnails
     * are not reloaded.
     */
    fun setDragPositions(sourcePos: Int, targetPos: Int) {
        val oldSource = dragSourcePosition
        val oldTarget = dropTargetPosition
        dragSourcePosition = sourcePos
        dropTargetPosition = targetPos
        // Notify only changed positions to avoid full rebind (avoids thumbnail flicker).
        setOf(oldSource, oldTarget, sourcePos, targetPos)
            .filter { it >= 0 && it < itemCount }
            .forEach { notifyItemChanged(it, PAYLOAD_DRAG_STATE) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ParticipantDashboardRowBinding.inflate(inflater, parent, false)
        CardShadowStyler.applySoftShadow(binding.root)
        return VH(binding, onScanCode, onRemove, onOpenPhotos, onFaceLookup)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
        holder.applyDragState(
            isDragSource = position == dragSourcePosition,
            isDropTarget = position == dropTargetPosition,
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_DRAG_STATE)) {
            // Only update visual drag state — no data rebind, so thumbnails don't flicker.
            holder.applyDragState(
                isDragSource = position == dragSourcePosition,
                isDropTarget = position == dropTargetPosition,
            )
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class VH(
        private val binding: ParticipantDashboardRowBinding,
        private val onScanCode: (Long) -> Unit,
        private val onRemove: (Long) -> Unit,
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

            val rank = if (row.isVolunteer) null else row.finishRank
            if (rank != null) {
                binding.participantRank.visibility = View.VISIBLE
                binding.participantRank.text =
                    binding.root.context.getString(R.string.participant_rank_fmt, rank)
            } else {
                binding.participantRank.visibility = View.GONE
            }

            val scanTrim = row.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
            binding.participantName.text = scanTrim ?: ""

            val info = registryInfoWithoutRedundantScan(row.registryInfo, scanTrim)
            if (!info.isNullOrBlank()) {
                binding.participantRegistryInfo.visibility = View.VISIBLE
                binding.participantRegistryInfo.text = info
            } else {
                binding.participantRegistryInfo.visibility = View.GONE
            }

            val startMs = row.raceStartedAtEpochMillis
            val finishMs = row.finishTimeEpochMillis
            val ctx = binding.root.context
            if (row.isVolunteer) {
                binding.participantMovingTime.visibility = View.GONE
                binding.participantFinishTime.text = ctx.getString(R.string.participant_volunteer_label)
                binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            } else {
                binding.participantMovingTime.visibility = View.VISIBLE
                if (startMs != null && finishMs != null) {
                    val delta = (finishMs - startMs).coerceAtLeast(0L)
                    binding.participantMovingTime.text = RaceUiFormatter.formatElapsed(delta)
                    binding.participantMovingTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                } else {
                    binding.participantMovingTime.text = ctx.getString(R.string.participant_dashboard_time_dash)
                    binding.participantMovingTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }

                if (finishMs != null) {
                    binding.participantFinishTime.text = RaceUiFormatter.formatTime(finishMs)
                    binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                } else {
                    binding.participantFinishTime.text = ctx.getString(R.string.participant_protocol_finish_empty)
                    binding.participantFinishTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
            }

            binding.participantThumb.isClickable = true
            binding.participantThumb.setOnClickListener { onOpenPhotos(row.participantId) }

            binding.btnFaceLookup.setOnClickListener { onFaceLookup(row.participantId) }
            binding.btnScanCode.setOnClickListener { onScanCode(row.participantId) }
            binding.btnRemoveParticipant.setOnClickListener { onRemove(row.participantId) }

            // Reset drag state on a full bind.
            applyDragState(isDragSource = false, isDropTarget = false)
        }

        /**
         * Updates only the card's visual drag state without touching text / thumbnail bindings.
         * Called from both full bind and partial-payload updates.
         */
        fun applyDragState(isDragSource: Boolean, isDropTarget: Boolean) {
            val card = binding.root as MaterialCardView
            val ctx = card.context
            when {
                isDragSource -> {
                    card.alpha = 0.45f
                    card.strokeWidth = 0
                }
                isDropTarget -> {
                    card.alpha = 1f
                    val strokePx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 3f, ctx.resources.displayMetrics,
                    ).toInt()
                    card.strokeWidth = strokePx
                    card.setStrokeColor(
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_pink)),
                    )
                }
                else -> {
                    card.alpha = 1f
                    card.strokeWidth = 0
                }
            }
        }
    }

    companion object {
        private const val PAYLOAD_DRAG_STATE = "drag_state"

        private const val REGISTRY_INFO_SEPARATOR = " · "

        /**
         * [RaceParticipantHashEntity.registryInfo] often repeats the scan code (merged from identity_registry).
         * The dedicated scan line already shows it once.
         */
        private fun registryInfoWithoutRedundantScan(
            registryInfo: String?,
            scanTrimmed: String?,
        ): String? {
            val raw = registryInfo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val scan = scanTrimmed?.trim()?.takeIf { it.isNotEmpty() } ?: return raw
            val parts = raw.split(REGISTRY_INFO_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            val filtered = parts.filter { it != scan }
            if (filtered.isEmpty()) return null
            return filtered.joinToString(REGISTRY_INFO_SEPARATOR)
        }

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
