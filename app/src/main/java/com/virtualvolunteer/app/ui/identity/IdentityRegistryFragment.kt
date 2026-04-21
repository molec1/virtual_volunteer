package com.virtualvolunteer.app.ui.identity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.core.os.bundleOf
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.ui.racedetail.ParticipantDetailFragment
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.FragmentIdentityRegistryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

/**
 * Lists all identities stored on this device (registry rows used when matching start photos).
 */
class IdentityRegistryFragment : Fragment() {

    private var _binding: FragmentIdentityRegistryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentIdentityRegistryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val adapter = IdentityRegistryAdapter { registryId ->
            viewLifecycleOwner.lifecycleScope.launch {
                val participantHashId = withContext(Dispatchers.IO) {
                    repo.resolveParticipantHashIdForRegistry(registryId)
                }
                if (participantHashId != null) {
                    findNavController().navigate(
                        R.id.action_identityRegistryFragment_to_participantDetailFragment,
                        bundleOf(ParticipantDetailFragment.ARG_PARTICIPANT_ID to participantHashId),
                    )
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.identity_registry_open_detail_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        binding.registryRecycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeIdentityRegistry().collect { rows ->
                    adapter.submitList(rows)
                    binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
