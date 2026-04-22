package com.virtualvolunteer.app.ui.racelist

import android.os.Bundle
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.databinding.FragmentRaceListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows saved races and creates a new race folder + Room row + XML snapshot.
 */
class RaceListFragment : Fragment() {

    private var _binding: FragmentRaceListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRaceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository

        val adapter = RaceListAdapter(
            imageLoadScope = viewLifecycleOwner.lifecycleScope,
            onOpen = { race ->
                val bundle = Bundle().apply { putString("raceId", race.id) }
                findNavController().navigate(R.id.action_race_list_to_race_detail, bundle)
            },
            onDelete = { race -> confirmDeleteRace(race) },
        )
        binding.raceRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.raceRecycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeAllRaces().collect { races ->
                adapter.submitList(races)
                binding.emptyView.visibility = if (races.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        binding.fabIdentityRegistry.setOnClickListener {
            findNavController().navigate(R.id.action_race_list_to_identity_registry)
        }

        binding.fabNewRace.setOnClickListener {
            lifecycleScope.launch { createRaceAndNavigate() }
        }
    }

    private fun confirmDeleteRace(race: RaceEntity) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_race_title)
            .setMessage(R.string.delete_race_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val app = requireActivity().application as VirtualVolunteerApp
                    app.raceRepository.deleteRace(race.id)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.toast_race_deleted),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.RED)
        }
        dialog.show()
    }

    private suspend fun createRaceAndNavigate() {
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        try {
            val raceId = repo.createNewRace()
            val bundle = Bundle().apply { putString("raceId", raceId) }
            findNavController().navigate(R.id.action_race_list_to_race_detail, bundle)
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), getString(R.string.race_created_nav_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
