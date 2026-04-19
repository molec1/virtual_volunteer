package com.virtualvolunteer.app.ui.racedetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.data.repository.ParticipantRacePhoto
import com.virtualvolunteer.app.databinding.ItemParticipantRacePhotoBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader

internal class ParticipantRacePhotoAdapter :
    ListAdapter<ParticipantRacePhoto, ParticipantRacePhotoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemParticipantRacePhotoBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemParticipantRacePhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ParticipantRacePhoto) {
            val bmp = PreviewImageLoader.loadThumbnail(item.absolutePath, maxSidePx = 512)
            binding.photoImage.setImageBitmap(bmp)
            binding.finishBadge.visibility =
                if (item.isFinishFrame) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ParticipantRacePhoto>() {
            override fun areItemsTheSame(
                oldItem: ParticipantRacePhoto,
                newItem: ParticipantRacePhoto,
            ): Boolean = oldItem.absolutePath == newItem.absolutePath

            override fun areContentsTheSame(
                oldItem: ParticipantRacePhoto,
                newItem: ParticipantRacePhoto,
            ): Boolean = oldItem == newItem
        }
    }
}
