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
        val hasEntries = participantRowCount > 0 || finishRecordCount > 0
        // Section title is always visible when expanded so the card is never truly bare
        binding.dashboardParticipantsTitle.visibility =
            if (participantsExpanded) View.VISIBLE else View.GONE
        // Empty-state placeholder when the protocol has no rows yet
        binding.protocolEmptyHint.visibility =
            if (participantsExpanded && !hasEntries) View.VISIBLE else View.GONE
        binding.participantsRecycler.visibility =
            if (participantsExpanded && hasEntries) View.VISIBLE else View.GONE
        binding.scrollContent.post {
            binding.participantsRecycler.requestLayout()
        }
    }
}
