package com.virtualvolunteer.app.ui.racedetail

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.mlkit.vision.face.Face
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.data.local.EmbeddingSourceType
import com.virtualvolunteer.app.databinding.BottomSheetManualFinishInputBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.domain.matching.FaceMatchEngine
import com.virtualvolunteer.app.domain.participants.RoomRaceParticipantPool
import com.virtualvolunteer.app.domain.time.PhotoTimestampResolver
import com.virtualvolunteer.app.ui.util.RaceUiFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Bottom sheet for manually adding a finish time for a participant.
 */
class ManualFinishInputBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManualFinishInputBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private lateinit var photoProcessor: RacePhotoProcessor

    private var selectedPhotoPath: String? = null
    private var selectedPhotoTime: Long? = null
    private var detectedEmbedding: FloatArray? = null

    private var manualCalendar: Calendar = Calendar.getInstance(TimeZone.getDefault())

    private val pickSingleFinishPhoto = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val tmp = File(ctx.cacheDir, "manual_finish_photo_${System.currentTimeMillis()}.img")
            try {
                UriFileCopy.copyToFile(ctx, uri, tmp)
                val ts = PhotoTimestampResolver.resolveEpochMillis(tmp)
                val bmp = photoProcessor.loadVisionBitmap(tmp)
                var embedding: FloatArray? = null
                if (bmp != null) {
                    val detected = photoProcessor.faces.detectFaces(bmp)
                    if (detected.isNotEmpty()) {
                        val face = detected.first() // Assume first face for manual photo
                        val crop = photoProcessor.cropFace(bmp, face)
                        if (crop != null) {
                            val embedResult = runCatching { photoProcessor.embedder.embed(crop) }
                            embedResult.onSuccess { embedding = it }
                            crop.recycle()
                        }
                    }
                    bmp.recycle()
                }
                withContext(Dispatchers.Main) {
                    selectedPhotoPath = tmp.absolutePath
                    selectedPhotoTime = ts
                    detectedEmbedding = embedding
                    updateUi()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "manual finish photo pick failed", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            } // finally { tmp.delete() } - don't delete yet, it's the source of the embedding
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetManualFinishInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        photoProcessor = RacePhotoProcessor(
            races = repo,
            faces = MlKitFaceDetector(),
            embedder = TfliteFaceEmbedder(requireActivity().applicationContext),
            matcher = FaceMatchEngine(),
            pool = RoomRaceParticipantPool(repo),
            appContext = requireActivity().applicationContext,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSelectFinishPhoto.setOnClickListener { pickSingleFinishPhoto.launch(arrayOf("image/*")) }
        binding.btnPickDate.setOnClickListener { showDatePicker() }
        binding.btnPickTime.setOnClickListener { showTimePicker() }
        binding.btnConfirmManualFinish.setOnClickListener { confirmManualFinish() }

        updateUi()
    }

    private fun updateUi() {
        if (selectedPhotoPath != null) {
            binding.textSelectedPhoto.text = File(selectedPhotoPath!!).name
            binding.textSelectedPhotoTime.text = selectedPhotoTime?.let { RaceUiFormatter.formatDateTimeWithSeconds(it) } ?: ""
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
        if (finishTime == null) {
            Toast.makeText(requireContext(), R.string.manual_finish_error_no_time, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
            try {
                val outcome = repo.recordManualFinishDetection(
                    raceId = raceId,
                    participantId = participantId,
                    finishTimeEpochMillis = finishTime,
                    sourcePhotoPath = selectedPhotoPath,
                    sourceEmbedding = detectedEmbedding,
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.manual_finish_success, outcome.officialProtocolFinishMillis?.let { RaceUiFormatter.formatTime(it) } ?: "?"),
                        Toast.LENGTH_LONG
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