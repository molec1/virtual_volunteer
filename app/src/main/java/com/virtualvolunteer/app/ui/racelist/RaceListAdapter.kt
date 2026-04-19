package com.virtualvolunteer.app.ui.racelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.databinding.RaceRowBinding
import com.virtualvolunteer.app.ui.util.RaceUiFormatter

class RaceListAdapter(
    private val onOpen: (RaceEntity) -> Unit,
) : ListAdapter<RaceEntity, RaceListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = RaceRowBinding.inflate(inflater, parent, false)
        return VH(binding, onOpen)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: RaceRowBinding,
        private val onOpen: (RaceEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RaceEntity) {
            binding.raceDate.text = RaceUiFormatter.formatDate(item.createdAtEpochMillis)
            binding.raceTime.text = RaceUiFormatter.formatTime(item.createdAtEpochMillis)
            binding.raceStatus.text = RaceUiFormatter.formatStatus(item.status)
            binding.root.setOnClickListener { onOpen(item) }
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
