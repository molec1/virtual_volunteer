package com.virtualvolunteer.app.domain.identity

/**
 * Result of matching a fresh face embedding against [com.virtualvolunteer.app.data.local.IdentityRegistryEntity].
 */
data class GlobalIdentityResolution(
    val registryId: Long,
    /** Display text merged from registry notes / scans when a match exists. */
    val registryInfo: String?,
    val matchedExisting: Boolean,
)
