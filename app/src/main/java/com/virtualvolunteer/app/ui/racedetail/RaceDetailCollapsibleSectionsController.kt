package com.virtualvolunteer.app.ui.racedetail

import android.view.View
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.databinding.FragmentRaceDetailBinding

class RaceDetailCollapsibleSectionsController {
    var offlineTestExpanded = false
    var participantsExpanded = true
    var pipelineDebugExpanded = false

    fun render(binding: FragmentRaceDetailBinding) {
        binding.offlineTestContent.visibility = if (offlineTestExpanded) View.VISIBLE else View.GONE
        binding.offlineTestExpandIcon.setImageResource(
            if (offlineTestExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
        )

        binding.participantsContent.visibility = if (participantsExpanded) View.VISIBLE else View.GONE
        binding.participantsExpandIcon.setImageResource(
            if (participantsExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
        )

        binding.pipelineDebugContent.visibility = if (pipelineDebugExpanded) View.VISIBLE else View.GONE
        binding.pipelineDebugExpandIcon.setImageResource(
            if (pipelineDebugExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
        )
    }

    fun toggleOfflineTest(binding: FragmentRaceDetailBinding) {
        offlineTestExpanded = !offlineTestExpanded
        render(binding)
    }

    fun toggleParticipants(binding: FragmentRaceDetailBinding) {
        participantsExpanded = !participantsExpanded
        render(binding)
    }

    fun togglePipelineDebug(binding: FragmentRaceDetailBinding) {
        pipelineDebugExpanded = !pipelineDebugExpanded
        render(binding)
    }
}
