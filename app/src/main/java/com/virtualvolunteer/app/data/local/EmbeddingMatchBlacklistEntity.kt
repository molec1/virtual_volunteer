package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Global blocklist of embedding pairs that must not be matched to each other.
 *
 * Symmetry is enforced by storing a canonical (aHash <= bHash) key; callers should canonicalize
 * before insert/query so a pair is blocked in both directions.
 */
@Entity(
    tableName = "embedding_match_blacklist",
    indices = [
        Index(value = ["aHash", "bHash"], unique = true),
        Index(value = ["aHash"]),
        Index(value = ["bHash"]),
    ],
)
data class EmbeddingMatchBlacklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val aHash: String,
    val bHash: String,
    val createdAtEpochMillis: Long,
)

