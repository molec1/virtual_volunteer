package com.virtualvolunteer.app.ui.racedetail

import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.export.RaceZipExporter
import com.virtualvolunteer.app.ui.camera.CameraCaptureFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RaceDetailRaceFlowActions(
    private val fragment: Fragment,
    private val raceId: String,
) {

    private val repo
        get() = (fragment.requireActivity().application as VirtualVolunteerApp).raceRepository

    fun onStartRaceClicked() {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            repo.markStarted(raceId, System.currentTimeMillis())
        }
    }

    fun onPreStartPhotoClicked() {
        fragment.findNavController().navigate(
            R.id.action_race_detail_to_cameraCaptureFragment,
            bundleOf(
                CameraCaptureFragment.ARG_RACE_ID to raceId,
                CameraCaptureFragment.ARG_CAPTURE_MODE to CameraCaptureFragment.MODE_START_PHOTO,
            ),
        )
    }

    fun onTakeFinishPhotoClicked() {
        fragment.findNavController().navigate(
            R.id.action_race_detail_to_cameraCaptureFragment,
            bundleOf(
                CameraCaptureFragment.ARG_RACE_ID to raceId,
                CameraCaptureFragment.ARG_CAPTURE_MODE to CameraCaptureFragment.MODE_FINISH_PHOTO,
            ),
        )
    }

    fun onFinishRaceClicked() {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.finish_race_confirm_title)
            .setMessage(R.string.finish_race_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_finish) { _, _ ->
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    repo.markFinished(raceId, System.currentTimeMillis())
                }
            }
            .show()
    }

    fun onExportClicked() {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val result = RaceZipExporter.exportRaceFolder(fragment.requireContext().applicationContext, raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { zip ->
                    fragment.lifecycleScope.launch(Dispatchers.IO) {
                        repo.markExported(raceId)
                    }
                    RaceDetailShareHelper.shareZip(fragment.requireContext(), zip)
                    Toast.makeText(fragment.requireContext(), R.string.export_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(fragment.requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onExportTimesCsvClicked() {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.exportTimesCsv(raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { file ->
                    RaceDetailShareHelper.shareCsv(
                        fragment.requireContext(),
                        file,
                        fragment.getString(R.string.export_csv_times_saved),
                    )
                }.onFailure {
                    Toast.makeText(fragment.requireContext(), R.string.export_csv_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onExportParticipantsCsvClicked() {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.exportParticipantsCsv(raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { file ->
                    RaceDetailShareHelper.shareCsv(
                        fragment.requireContext(),
                        file,
                        fragment.getString(R.string.export_csv_participants_saved),
                    )
                }.onFailure {
                    Toast.makeText(fragment.requireContext(), R.string.export_csv_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
