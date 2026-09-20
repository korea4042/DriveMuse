package ai.drivemuse.domain

/**
 * Reduced selection mode.
 *
 * Spotify Developer Policy III.13 forbids analysing Spotify content or service data into listening
 * metrics and user profiles. Blocking the AI call was only half of that: the local ranker scored
 * candidates on `affinity`, `recognisability` (provider popularity), `freshness` and learned
 * observation scores, all of which are exactly those derived metrics.
 *
 * So this mode does not rank. It applies the user's own stated rules — the survey's exclusions and
 * the artist cap — and then orders deterministically by something that carries no judgement about
 * the recording. The order still varies between sessions, because the same eight tracks forever is
 * a worse product than an arbitrary order, but it varies on the session id rather than on anything
 * learned about the listener.
 *
 * When the rules leave fewer tracks than a batch, the shortfall is returned as a shortfall. There
 * is deliberately no path back to the scored ranking: a silent fallback would reinstate the very
 * signals this mode exists to stop using.
 */
object DirectInputSelector {

    data class Outcome(
        val tracks: List<Track>,
        /** Candidates that passed the user's rules, before the batch was cut. */
        val eligible: Int,
        val requested: Int
    ) {
        val short get() = tracks.size < requested
    }

    /**
     * Stable per (session, track) and uniform enough to shuffle a pool. Not a hash of anything the
     * listener did; the session id is a random UUID.
     */
    private fun order(sessionId: String, trackId: String): Int {
        var h = 0x811C9DC5.toInt()
        for (c in sessionId) h = (h xor c.code) * 0x01000193
        for (c in trackId) h = (h xor c.code) * 0x01000193
        return h
    }

    fun select(
        candidates: List<Track>,
        constraints: Constraints,
        sessionId: String,
        excluded: Set<String> = emptySet(),
        count: Int = Policy.BATCH_SIZE,
        maxPerArtist: Int = Policy.MAX_PER_ARTIST
    ): Outcome {
        val eligible = candidates
            .distinctBy { it.id }
            .filter { it.id !in excluded && Policy.validTrackId(it.id) && constraints.allows(it) }
            .sortedBy { order(sessionId, it.id) }

        val perArtist = mutableMapOf<String, Int>()
        val picked = mutableListOf<Track>()
        for (track in eligible) {
            if (picked.size == count) break
            val key = track.artist.trim().lowercase()
            val used = perArtist[key] ?: 0
            if (used >= maxPerArtist) continue
            perArtist[key] = used + 1
            picked += track
        }
        // The cap is soft: a full batch beats a perfectly spread one (§30).
        if (picked.size < count) {
            for (track in eligible) {
                if (picked.size == count) break
                if (picked.none { it.id == track.id }) picked += track
            }
        }
        return Outcome(picked, eligible.size, count)
    }
}
