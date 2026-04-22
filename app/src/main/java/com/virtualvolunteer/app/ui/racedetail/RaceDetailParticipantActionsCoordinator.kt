package com.virtualvolunteer.app.ui.racedetail

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.integration.android.IntentIntegrator
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.ui.scan.BarcodeScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RaceDetailParticipantActionsCoordinator(
    private val fragment: Fragment,
    private val raceId: String,
    private val runWithCameraPermission: (action: () -> Unit) -> Unit,
    private val launchBarcodeScanActivity: () -> Unit,
) {
    var pendingScanParticipantId: Long? = null
        private set

    fun consumePendingScanParticipantId(): Long? {
        val id = pendingScanParticipantId
        pendingScanParticipantId = null
        return id
    }

    fun showParticipantNameEditor(participantId: Long, currentName: String?) {
        val input = EditText(fragment.requireContext()).apply {
            setText(currentName.orEmpty())
            hint = fragment.getString(R.string.participant_tap_to_name)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.participant_name_dialog_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    (fragment.requireActivity().application as VirtualVolunteerApp).raceRepository
                        .updateParticipantDisplayName(raceId, participantId, text)
                }
            }
            .show()
    }

    fun openBarcodeScan(participantId: Long) {
        pendingScanParticipantId = participantId
        runWithCameraPermission {
            launchBarcodeScanActivity()
        }
    }

    fun openParticipantPhotos(participantId: Long) {
        ParticipantPhotosBottomSheet.newInstance(raceId, participantId)
            .show(fragment.childFragmentManager, "participantPhotos")
    }

    fun openParticipantLookupForRow(participantId: Long) {
        ParticipantLookupBottomSheet.newInstance(raceId, donorParticipantId = participantId)
            .show(fragment.childFragmentManager, "participantLookupRow")
    }

    fun openManualFinishBottomSheet() {
        ManualFinishInputBottomSheet.newInstance(raceId)
            .show(fragment.childFragmentManager, "manualFinishInput")
    }

    fun openRaceParticipantPhotos(raceId: String, participantId: Long) {
        RaceParticipantPhotosBottomSheet.newInstance(raceId, participantId)
            .show(fragment.childFragmentManager, "raceParticipantPhotos")
    }

    fun confirmRemoveParticipant(participantId: Long) {
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.delete_participant_title)
            .setMessage(R.string.delete_participant_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    (fragment.requireActivity().application as VirtualVolunteerApp).raceRepository
                        .removeParticipantFromRace(raceId, participantId)
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.RED)
        }
        dialog.show()
    }
}

fun extractScanText(data: Intent?): String? {
    if (data == null) return null
    val parsed = IntentIntegrator.parseActivityResult(
        IntentIntegrator.REQUEST_CODE,
        Activity.RESULT_OK,
        data,
    )
    if (parsed != null && parsed.contents != null) return parsed.contents
    data.getStringExtra("SCAN_RESULT")?.let { return it }
    return null
}
