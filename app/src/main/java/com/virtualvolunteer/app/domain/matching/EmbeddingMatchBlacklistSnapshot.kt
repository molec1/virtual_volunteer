package com.virtualvolunteer.app.domain.matching

import java.security.MessageDigest

/**
 * In-memory snapshot of globally blacklisted embedding pairs.
 *
 * Keys are canonicalized so (A,B) blocks both directions.
 */
class EmbeddingMatchBlacklistSnapshot(
    pairsCanonical: Set<String>,
) {
    private val keys: Set<String> = pairsCanonical

    fun isBlockedByEmbeddingStrings(aEmbeddingCommaSeparated: String, bEmbeddingCommaSeparated: String): Boolean {
        val a = EmbeddingPairKey.hashEmbeddingString(aEmbeddingCommaSeparated)
        val b = EmbeddingPairKey.hashEmbeddingString(bEmbeddingCommaSeparated)
        return keys.contains(EmbeddingPairKey.canonicalKeyFromHashes(a, b))
    }
}

object EmbeddingPairKey {
    fun canonicalKeyFromHashes(aHash: String, bHash: String): String {
        return if (aHash <= bHash) "$aHash|$bHash" else "$bHash|$aHash"
    }

    fun hashEmbeddingString(embeddingCommaSeparated: String): String {
        val normalized = embeddingCommaSeparated.trim()
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

