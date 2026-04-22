package com.virtualvolunteer.app.ui.racedetail

import android.view.View
import com.virtualvolunteer.app.databinding.FragmentRaceDetailBinding

internal object RaceDetailParticipantSectionUi {
    fun applyParticipantSectionVisibility(
        binding: FragmentRaceDetailBinding,
        participantRowCount: Int,
        finishRecordCount: Int,
        participantsExpanded: Boolean,
    ) {
        val showProtocol = participantRowCount > 0 || finishRecordCount > 0
        binding.dashboardParticipantsTitle.visibility =
            if (showProtocol && participantsExpanded) View.VISIBLE else View.GONE
        binding.participantsRecycler.visibility =
            if (showProtocol && participantsExpanded) View.VISIBLE else View.GONE
        binding.scrollContent.post {
            binding.participantsRecycler.requestLayout()
        }
    }
}
