package com.virtualvolunteer.app.ui.racedetail

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.repository.RaceRepository
import com.virtualvolunteer.app.databinding.BottomSheetRaceParticipantPhotosBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet to show a grid of photos for a specific participant in a specific race.
 */
class RaceParticipantPhotosBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRaceParticipantPhotosBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private val participantId: Long
        get() = requireArguments().getLong(ARG_PARTICIPANT_ID) ?: error("participantId missing")

    private lateinit var photoAdapter: ParticipantRacePhotoAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetRaceParticipantPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository

        photoAdapter = ParticipantRacePhotoAdapter(viewLifecycleOwner.lifecycleScope)
        binding.photosRecycler.adapter = photoAdapter
        binding.photosRecycler.layoutManager = GridLayoutManager(requireContext(), 3)

        lifecycleScope.launch(Dispatchers.IO) {
            val photos = repo.listParticipantRacePhotos(raceId, participantId)
            val participant = repo.getParticipantHashById(participantId)
            val race = repo.getRace(raceId)
            withContext(Dispatchers.Main) {
                if (photos.isEmpty()) {
                    binding.emptyHint.visibility = View.VISIBLE
                } else {
                    binding.emptyHint.visibility = View.GONE
                }
                binding.sheetSubtitle.text = buildString {
                    append(participant?.displayName ?: "#${participant?.id}")
                    append(" (")
                    append(race?.id?.take(8) ?: "Unknown Race")
                    append(")")
                }
                photoAdapter.submitList(photos)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RACE_ID = "raceId"
        private const val ARG_PARTICIPANT_ID = "participantId"

        fun newInstance(raceId: String, participantId: Long): RaceParticipantPhotosBottomSheet = RaceParticipantPhotosBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_RACE_ID, raceId)
                putLong(ARG_PARTICIPANT_ID, participantId)
            }
        }
    }
}