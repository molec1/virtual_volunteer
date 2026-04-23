package com.virtualvolunteer.app.ui.racedetail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.databinding.ItemRaceEventPhotoThumbBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RaceEventPhotosGridAdapter(
    private val imageLoadScope: CoroutineScope,
    private val onOpen: (absolutePath: String, index: Int) -> Unit,
) : ListAdapter<String, RaceEventPhotosGridAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemRaceEventPhotoThumbBinding.inflate(inflater, parent, false)
        return VH(binding, imageLoadScope, onOpen)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position)
    }

    class VH(
        private val binding: ItemRaceEventPhotoThumbBinding,
        private val imageLoadScope: CoroutineScope,
        private val onOpen: (absolutePath: String, index: Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null
        private var bindGeneration: Int = 0

        fun bind(path: String, index: Int) {
            loadJob?.cancel()
            val gen = ++bindGeneration
            binding.thumbImage.setImageBitmap(null)
            binding.thumbImage.setBackgroundResource(R.drawable.bg_placeholder_photo)
            val isFinish = path.contains("finish_photos")
            binding.finishBadge.visibility = if (isFinish) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onOpen(path, index) }

            loadJob = imageLoadScope.launch(Dispatchers.Default) {
                val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 400)
                withContext(Dispatchers.Main) {
                    if (gen != bindGeneration) return@withContext
                    if (bmp != null) {
                        binding.thumbImage.background = null
                        binding.thumbImage.setImageBitmap(bmp)
                    } else {
                        binding.thumbImage.setImageBitmap(null)
                        binding.thumbImage.setBackgroundResource(R.drawable.bg_placeholder_photo)
                    }
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        }
    }
}
