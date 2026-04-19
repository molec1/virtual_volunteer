package com.virtualvolunteer.app.data.local

/**
 * Origin of a stored face embedding vector for a race participant.
 */
enum class EmbeddingSourceType {
    /** Start-line (or pre-start) crowd photo ingestion. */
    START,

    /** Finish-line automatic cosine match at or above threshold. */
    FINISH_AUTO,

    /** Embedding merged from another participant row (manual scan / duplicate resolution). */
    FINISH_MANUAL_LINK,
}
