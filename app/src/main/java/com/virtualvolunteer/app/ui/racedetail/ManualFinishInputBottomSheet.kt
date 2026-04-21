package com.virtualvolunteer.app.ui.racedetail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.BottomSheetManualFinishInputBinding
import com.virtualvolunteer.app.databinding.ItemManualFinishPhotoBinding
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Bottom sheet for manually adding a finish time: pick from this race's [finish_photos] folder or enter time.
 */
class ManualFinishInputBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManualFinishInputBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private var selectedPhotoPath: String? = null
    private var selectedPhotoTime: Long? = null

    private var manualCalendar: Calendar = Calendar.getInstance(TimeZone.getDefault())

    private lateinit var finishPhotoAdapter: FinishPhotoPickAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetManualFinishInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        finishPhotoAdapter = FinishPhotoPickAdapter(viewLifecycleOwner.lifecycleScope) { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val ts = PhotoTimestampResolver.resolveEpochMillis(File(path))
                withContext(Dispatchers.Main) {
                    selectedPhotoPath = path
                    selectedPhotoTime = ts
                    updateUi()
                }
            }
        }
        binding.finishPhotosRecycler.layoutManager =
            LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.finishPhotosRecycler.adapter = finishPhotoAdapter

        lifecycleScope.launch(Dispatchers.IO) {
            val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
            val paths = repo.listFinishPhotoPathsForRace(raceId)
            withContext(Dispatchers.Main) {
                finishPhotoAdapter.submitList(paths)
            }
        }

        binding.btnPickDate.setOnClickListener { showDatePicker() }
        binding.btnPickTime.setOnClickListener { showTimePicker() }
        binding.btnConfirmManualFinish.setOnClickListener { confirmManualFinish() }

        updateUi()
    }

    private fun updateUi() {
        if (selectedPhotoPath != null) {
            binding.textSelectedPhoto.text = File(selectedPhotoPath!!).name
            binding.textSelectedPhotoTime.text =
                selectedPhotoTime?.let { RaceUiFormatter.formatDateTimeWithSeconds(it) } ?: ""
            binding.groupManualTime.visibility = View.GONE
            binding.groupPhotoTime.visibility = View.VISIBLE
        } else {
            binding.textSelectedPhoto.text = getString(R.string.manual_finish_no_photo_selected)
            binding.groupManualTime.visibility = View.VISIBLE
            binding.groupPhotoTime.visibility = View.GONE
            updateManualTimeUi()
        }
    }

    private fun updateManualTimeUi() {
        binding.textManualDate.text = RaceUiFormatter.formatDate(manualCalendar.timeInMillis)
        binding.textManualTime.text = RaceUiFormatter.formatTime(manualCalendar.timeInMillis)
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                manualCalendar.set(year, month, dayOfMonth)
                updateUi()
            },
            manualCalendar.get(Calendar.YEAR),
            manualCalendar.get(Calendar.MONTH),
            manualCalendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun showTimePicker() {
        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                manualCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                manualCalendar.set(Calendar.MINUTE, minute)
                updateUi()
            },
            manualCalendar.get(Calendar.HOUR_OF_DAY),
            manualCalendar.get(Calendar.MINUTE),
            true,
        ).show()
    }

    private fun confirmManualFinish() {
        val participantIdText = binding.participantIdInput.text?.toString()?.trim()
        val participantId = participantIdText?.toLongOrNull()

        if (participantId == null) {
            Toast.makeText(requireContext(), R.string.manual_finish_error_no_participant, Toast.LENGTH_SHORT).show()
            return
        }

        val finishTime = selectedPhotoTime ?: manualCalendar.timeInMillis

        lifecycleScope.launch(Dispatchers.IO) {
            val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
            try {
                val outcome = repo.recordManualFinishDetection(
                    raceId = raceId,
                    participantId = participantId,
                    finishTimeEpochMillis = finishTime,
                    sourcePhotoPath = selectedPhotoPath,
                    sourceEmbedding = null,
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.manual_finish_success,
                            outcome.officialProtocolFinishMillis?.let { RaceUiFormatter.formatTime(it) } ?: "?",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "manual finish failed", e)
                val msg = when (e) {
                    is IllegalArgumentException -> R.string.manual_finish_error_invalid_participant_id
                    else -> R.string.manual_finish_error_generic
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ManualFinishInputBottomSheet"
        private const val ARG_RACE_ID = "raceId"

        fun newInstance(raceId: String) = ManualFinishInputBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_RACE_ID, raceId)
            }
        }
    }
}

private class FinishPhotoPickAdapter(
    private val imageLoadScope: CoroutineScope,
    private val onPick: (String) -> Unit,
) : ListAdapter<String, FinishPhotoPickAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemManualFinishPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, imageLoadScope, onPick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemManualFinishPhotoBinding,
        private val imageLoadScope: CoroutineScope,
        private val onPick: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null
        private var bindGeneration: Int = 0

        fun bind(path: String) {
            loadJob?.cancel()
            val gen = ++bindGeneration
            binding.root.setOnClickListener { onPick(path) }
            binding.finishPhotoThumb.setImageBitmap(null)
            binding.finishPhotoThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
            loadJob = imageLoadScope.launch(Dispatchers.Default) {
                val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 256)
                withContext(Dispatchers.Main) {
                    if (gen != bindGeneration) return@withContext
                    if (bmp != null) {
                        binding.finishPhotoThumb.background = null
                        binding.finishPhotoThumb.setImageBitmap(bmp)
                    } else {
                        binding.finishPhotoThumb.setImageBitmap(null)
                        binding.finishPhotoThumb.setBackgroundResource(R.drawable.bg_placeholder_photo)
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
