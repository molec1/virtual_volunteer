package com.virtualvolunteer.app.ui.racedetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.repository.ParticipantRacePhoto
import com.virtualvolunteer.app.databinding.ItemParticipantRacePhotoBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ParticipantRacePhotoAdapter(
    private val imageLoadScope: CoroutineScope,
) : ListAdapter<ParticipantRacePhoto, ParticipantRacePhotoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemParticipantRacePhotoBinding.inflate(inflater, parent, false)
        return VH(binding, imageLoadScope)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemParticipantRacePhotoBinding,
        private val imageLoadScope: CoroutineScope,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null
        private var bindGeneration: Int = 0

        fun bind(item: ParticipantRacePhoto) {
            loadJob?.cancel()
            val gen = ++bindGeneration
            binding.photoImage.setImageBitmap(null)
            binding.photoImage.setBackgroundResource(R.drawable.bg_placeholder_photo)
            binding.finishBadge.visibility =
                if (item.isFinishFrame) View.VISIBLE else View.GONE

            val path = item.absolutePath
            loadJob = imageLoadScope.launch(Dispatchers.Default) {
                val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 720)
                withContext(Dispatchers.Main) {
                    if (gen != bindGeneration) return@withContext
                    if (bmp != null) {
                        binding.photoImage.background = null
                        binding.photoImage.setImageBitmap(bmp)
                    } else {
                        binding.photoImage.setImageBitmap(null)
                        binding.photoImage.setBackgroundResource(R.drawable.bg_placeholder_photo)
                    }
                }
            }
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
