package com.virtualvolunteer.app.ui.identity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.FragmentIdentityRegistryBinding
import com.virtualvolunteer.app.ui.racedetail.ParticipantDetailFragment
import com.virtualvolunteer.app.ui.racedetail.RaceDetailShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists identities on this device that have a **scanned code**; refreshes registry face thumbnails
 * from linked race participants when the stored path is missing or invalid.
 */
class IdentityRegistryFragment : Fragment() {

    private var _binding: FragmentIdentityRegistryBinding? = null
    private val binding get() = _binding!!

    private val pickDeviceIdentitiesJsonDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = requireActivity().application as VirtualVolunteerApp
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File.createTempFile("device-identities-import", ".json", ctx.cacheDir)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.import_device_identities_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val result = app.raceRepository.importDeviceScannedIdentitiesJson(tmp)
                val skippedLines = result.skippedNullOrBlankBarcode + result.skippedMalformedEmbeddingVectors
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.import_device_identities_done,
                            result.identitiesCreated,
                            result.identitiesUpdated,
                            result.registryFaceVectorsAppliedFromFile,
                            result.fileVectorsNotStoredRegistryFacePresent,
                            skippedLines,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.import_device_identities_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                tmp.delete()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentIdentityRegistryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val spanCount = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        binding.registryRecycler.layoutManager = GridLayoutManager(requireContext(), spanCount)

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

        binding.btnExportParticipantsJson.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dir = File(requireContext().filesDir, "export/device_identities").apply { mkdirs() }
                val out = File(dir, "device_identities_${System.currentTimeMillis()}.json")
                try {
                    repo.exportDeviceScannedIdentitiesJson(out)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        RaceDetailShareHelper.shareJson(
                            requireContext(),
                            out,
                            getString(R.string.export_device_identities_saved),
                        )
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        Toast.makeText(
                            requireContext(),
                            R.string.export_device_identities_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        binding.btnImportParticipantsJson.setOnClickListener {
            pickDeviceIdentitiesJsonDocument.launch(arrayOf("application/json", "application/*", "*/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    repo.consolidateAllScanMerges()
                }
                repo.observeIdentityRegistry().collect { rows ->
                    adapter.submitList(rows)
                    binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        repo.ensureIdentityRegistryThumbnailsFromLinkedParticipants(rows)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
