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
        val requested: Int,
        /** How many of the chosen tracks came from a name the user typed. */
        val seeded: Int = 0,
        /**
         * The per-artist cap had to be broken to fill the batch (§30). Soft is not silent: the user
         * gets told their batch leans on one artist because the pool left no alternative.
         */
        val capRelaxed: Boolean = false
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
        maxPerArtist: Int = Policy.MAX_PER_ARTIST,
        /**
         * Names the user typed in the survey. Preferring these is not a derived metric: it is the
         * listener's own words, which is the one signal this mode is built to act on.
         */
        seeds: Collection<String> = emptyList()
    ): Outcome {
        val eligible = candidates
            .distinctBy { it.id }
            .filter { it.id !in excluded && Policy.validTrackId(it.id) && constraints.allows(it) }
            // Seeded first, then the deterministic order within each group.
            .sortedWith(compareByDescending<Track> { SurveySeeds.matches(it, seeds) }.thenBy { order(sessionId, it.id) })

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
        val cappedAt = picked.size
        if (picked.size < count) {
            for (track in eligible) {
                if (picked.size == count) break
                if (picked.none { it.id == track.id }) picked += track
            }
        }
        return Outcome(picked, eligible.size, count, picked.count { SurveySeeds.matches(it, seeds) }, capRelaxed = picked.size > cappedAt)
    }
}

/**
 * The artists and titles the user typed into the survey, as separate terms.
 *
 * The field asks for "곡 또는 아티스트", so people list several. The whole string used to be sent
 * as one Spotify query, and "에스파,카리나,엔믹스" matches nothing. Splitting is on separators only,
 * never on spaces: a great many names contain one.
 */
object SurveySeeds {
    private val separators = Regex("[,\\uFF0C/、|\\n\\r]+")

    fun terms(answers: List<SurveyAnswer>): List<String> = answers
        .filter { it.status == AnswerStatus.ANSWERED && it.freeText.isNotBlank() }
        // The seed question first: those are the names the user actually chose to type.
        .sortedByDescending { it.question.id == "Q7" }
        .flatMap { it.freeText.split(separators) }
        .map { it.trim().take(60) }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    /** Loose containment, so "에스파" matches the artist field however it is punctuated. */
    fun matches(track: Track, terms: Collection<String>): Boolean {
        if (terms.isEmpty()) return false
        val haystack = (track.artist + " " + track.title).lowercase().replace(" ", "")
        return terms.any { term ->
            val needle = term.lowercase().replace(" ", "")
            needle.length >= 2 && haystack.contains(needle)
        }
    }
}
