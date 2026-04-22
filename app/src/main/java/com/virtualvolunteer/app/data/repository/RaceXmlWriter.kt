package com.virtualvolunteer.app.data.repository

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import com.virtualvolunteer.app.data.local.RaceEntity
import com.virtualvolunteer.app.data.xml.RaceXmlIo
import com.virtualvolunteer.app.data.xml.RaceXmlSnapshot

internal class RaceXmlWriter(private val appContext: Context) {
    fun write(entity: RaceEntity) {
        val snap = RaceXmlSnapshot(
            id = entity.id,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            startedAtEpochMillis = entity.startedAtEpochMillis,
            finishedAtEpochMillis = entity.finishedAtEpochMillis,
            status = entity.status,
        )
        RaceXmlIo.write(RacePaths.raceXml(appContext, entity.id), snap)
    }
}
