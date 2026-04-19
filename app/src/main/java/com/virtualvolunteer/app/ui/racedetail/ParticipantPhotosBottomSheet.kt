package com.virtualvolunteer.app.ui.racedetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.BottomSheetParticipantPhotosBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grid of distinct image files for one participant in a race; finish-line source photos show a tick.
 */
class ParticipantPhotosBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetParticipantPhotosBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private val participantId: Long
        get() = requireArguments().getLong(ARG_PARTICIPANT_ID)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetParticipantPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = ParticipantRacePhotoAdapter()
        binding.photoRecycler.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.photoRecycler.adapter = adapter

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val photos = withContext(Dispatchers.IO) {
                    repo.listParticipantRacePhotos(raceId, participantId)
                }
                val empty = photos.isEmpty()
                binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
                binding.photoRecycler.visibility = if (empty) View.GONE else View.VISIBLE
                adapter.submitList(photos)
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

        fun newInstance(raceId: String, participantId: Long): ParticipantPhotosBottomSheet =
            ParticipantPhotosBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RACE_ID, raceId)
                    putLong(ARG_PARTICIPANT_ID, participantId)
                }
            }
    }
}
