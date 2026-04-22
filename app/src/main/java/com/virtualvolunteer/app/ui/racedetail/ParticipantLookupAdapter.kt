package com.virtualvolunteer.app.ui.racedetail

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.repository.ScannedIdentityLookupRank
import com.virtualvolunteer.app.databinding.ItemParticipantLookupResultBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class ParticipantLookupAdapter(
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
            // Clicks must be on the card: it is clickable/focusable and consumes touches; the outer
            // LinearLayout root never receives taps.
            binding.participantCard.setOnClickListener { onClick(result) }
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
