package com.virtualvolunteer.app.ui.racelist

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.io.File
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.databinding.RaceRowBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RaceListAdapter(
    private val imageLoadScope: CoroutineScope,
    private val onOpen: (RaceEntity) -> Unit,
    private val onDelete: (RaceEntity) -> Unit,
) : ListAdapter<RaceEntity, RaceListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RaceRowBinding.inflate(inflater, parent, false)
        return VH(binding, imageLoadScope, onOpen, onDelete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: RaceRowBinding,
        private val imageLoadScope: CoroutineScope,
        private val onOpen: (RaceEntity) -> Unit,
        private val onDelete: (RaceEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var bindGeneration: Int = 0

        fun bind(item: RaceEntity) {
            val gen = ++bindGeneration
            binding.raceDate.text = RaceUiFormatter.formatDate(item.createdAtEpochMillis)
            binding.raceTime.text = RaceUiFormatter.formatTime(item.createdAtEpochMillis)
            binding.raceStatus.text = RaceUiFormatter.formatStatus(item.status)
            binding.raceRowMain.setOnClickListener { onOpen(item) }
            binding.btnDeleteRace.setOnClickListener { onDelete(item) }

            binding.raceStartThumb.visibility = View.GONE
            binding.raceStartThumb.setImageBitmap(null)
            binding.raceStartThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)

            val raceId = item.id
            val app = binding.root.context.applicationContext as VirtualVolunteerApp
            val repo = app.raceRepository
            imageLoadScope.launch(Dispatchers.IO) {
                val quick = item.listThumbnailPath?.trim()?.takeIf { it.isNotEmpty() && File(it).exists() }
                val path = quick ?: repo.ensureRaceListThumbnail(raceId)
                val bmp = path?.let { p ->
                    BitmapFactory.decodeFile(p)
                        ?: PreviewImageLoader.loadThumbnailOrientedInset(p, maxSidePx = 256)
                }
                withContext(Dispatchers.Main) {
                    if (gen != bindGeneration) return@withContext
                    if (bmp != null) {
                        binding.raceStartThumb.setImageBitmap(bmp)
                        binding.raceStartThumb.background = null
                        binding.raceStartThumb.visibility = View.VISIBLE
                    } else {
                        binding.raceStartThumb.visibility = View.GONE
                    }
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RaceEntity>() {
            override fun areItemsTheSame(oldItem: RaceEntity, newItem: RaceEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: RaceEntity, newItem: RaceEntity): Boolean =
                oldItem == newItem
        }
    }
}
