package com.virtualvolunteer.app.ui.racedetail

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.repository.FaceBoundingBox
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
    private val onPhotoClick: (ParticipantRacePhoto) -> Unit = {},
) : ListAdapter<ParticipantRacePhoto, ParticipantRacePhotoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemParticipantRacePhotoBinding.inflate(inflater, parent, false)
        return VH(binding, imageLoadScope, onPhotoClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemParticipantRacePhotoBinding,
        private val imageLoadScope: CoroutineScope,
        private val onPhotoClick: (ParticipantRacePhoto) -> Unit,
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
            binding.root.setOnClickListener { onPhotoClick(item) }

            loadJob = imageLoadScope.launch(Dispatchers.Default) {
                val bmp = when {
                    !item.faceCropPath.isNullOrBlank() ->
                        PreviewImageLoader.loadThumbnailOrientedInset(item.faceCropPath, maxSidePx = 360)
                    item.faceBoundingBox != null ->
                        cropFromBoundingBox(item.absolutePath, item.faceBoundingBox)
                    else ->
                        PreviewImageLoader.loadThumbnailOriented(item.absolutePath, maxSidePx = 720)
                }
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

        private fun cropFromBoundingBox(path: String, box: FaceBoundingBox): Bitmap? {
            val full = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 600) ?: return null
            return try {
                val sx = full.width.toFloat() / box.sourceWidth
                val sy = full.height.toFloat() / box.sourceHeight
                val padX = (box.right - box.left) * 0.28f
                val padY = (box.bottom - box.top) * 0.28f
                val l = ((box.left - padX) * sx).toInt().coerceIn(0, full.width - 1)
                val t = ((box.top - padY) * sy).toInt().coerceIn(0, full.height - 1)
                val r = ((box.right + padX) * sx).toInt().coerceIn(l + 1, full.width)
                val b = ((box.bottom + padY) * sy).toInt().coerceIn(t + 1, full.height)
                val crop = Bitmap.createBitmap(full, l, t, r - l, b - t)
                if (crop !== full) full.recycle()
                crop
            } catch (_: Exception) {
                full.recycle()
                null
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
