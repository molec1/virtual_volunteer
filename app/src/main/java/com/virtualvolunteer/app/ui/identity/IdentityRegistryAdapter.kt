package com.virtualvolunteer.app.ui.identity

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.local.IdentityRegistryEntity
import com.virtualvolunteer.app.databinding.ItemIdentityRegistryRowBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import java.io.File

/**
 * Rows for [IdentityRegistryEntity]: scan code and notes captured on this device.
 */
class IdentityRegistryAdapter(
    private val onItemClick: (participantId: Long) -> Unit,
    private val onDeleteClick: (registryId: Long) -> Unit,
) : ListAdapter<IdentityRegistryEntity, IdentityRegistryAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemIdentityRegistryRowBinding.inflate(inflater, parent, false)
        return VH(binding, onItemClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemIdentityRegistryRowBinding,
        private val onItemClick: (Long) -> Unit,
        private val onDeleteClick: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: IdentityRegistryEntity) {
            binding.registryRowMain.setOnClickListener { onItemClick(row.id) }
            binding.btnDeleteIdentity.setOnClickListener { onDeleteClick(row.id) }

            val thumbPath = row.primaryThumbnailPhotoPath?.takeIf { File(it).exists() }
            if (!thumbPath.isNullOrBlank()) {
                val bmp = PreviewImageLoader.loadThumbnailOrientedInset(thumbPath, maxSidePx = 512)
                binding.registryThumb.setImageBitmap(bmp)
                if (bmp != null) {
                    binding.registryThumb.background = null
                } else {
                    binding.registryThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
                }
            } else {
                binding.registryThumb.setImageBitmap(null)
                binding.registryThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
            }

            val ctx = binding.root.context
            val idLabel = ctx.getString(R.string.identity_registry_id_fmt, row.id)
            val scan = row.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
                ?: ctx.getString(R.string.identity_registry_no_scan)

            // "#N  code" with #N in accent_pink bold
            val ssb = SpannableStringBuilder()
            val idEnd = idLabel.length
            ssb.append(idLabel)
            ssb.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.accent_pink)),
                0, idEnd,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            ssb.setSpan(
                StyleSpan(Typeface.BOLD),
                0, idEnd,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            ssb.append("  ")
            ssb.append(scan)
            binding.registryIdText.text = ssb

            binding.registryCreatedText.text = RaceUiFormatter.formatDateTime(row.createdAtEpochMillis)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<IdentityRegistryEntity>() {
            override fun areItemsTheSame(
                oldItem: IdentityRegistryEntity,
                newItem: IdentityRegistryEntity,
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: IdentityRegistryEntity,
                newItem: IdentityRegistryEntity,
            ): Boolean = oldItem == newItem
        }
    }
}
