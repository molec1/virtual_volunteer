package com.virtualvolunteer.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Device-local identity store: face embedding plus optional QR/barcode payload for enrichment.
 * Matched during start-photo ingestion before creating race-local participant rows.
 */
@Entity(tableName = "identity_registry")
data class IdentityRegistryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Comma-separated floats (same format as race embeddings). */
    val embedding: String,
    /** Latest scanned QR/barcode text linked to this identity. */
    val scannedPayload: String? = null,
    /** Optional notes / merged info shown when matched. */
    val notes: String? = null,
    val createdAtEpochMillis: Long,
    val primaryThumbnailPhotoPath: String? = null,
)
