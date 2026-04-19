package com.virtualvolunteer.app.ui.identity

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.local.IdentityRegistryEntity
import com.virtualvolunteer.app.databinding.ItemIdentityRegistryRowBinding
import com.virtualvolunteer.app.ui.util.RaceUiFormatter

/**
 * Rows for [IdentityRegistryEntity]: scan code and notes captured on this device.
 */
class IdentityRegistryAdapter :
    ListAdapter<IdentityRegistryEntity, IdentityRegistryAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemIdentityRegistryRowBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemIdentityRegistryRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: IdentityRegistryEntity) {
            binding.registryIdText.text =
                binding.root.context.getString(R.string.identity_registry_id_fmt, row.id)

            val scan = row.scannedPayload?.trim()?.takeIf { it.isNotEmpty() }
            binding.registryScanText.text =
                scan ?: binding.root.context.getString(R.string.identity_registry_no_scan)

            val notes = row.notes?.trim()?.takeIf { it.isNotEmpty() }
            if (notes != null) {
                binding.registryNotesText.visibility = View.VISIBLE
                binding.registryNotesText.text = notes
            } else {
                binding.registryNotesText.visibility = View.GONE
            }

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
