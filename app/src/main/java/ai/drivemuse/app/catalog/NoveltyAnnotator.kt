package ai.drivemuse.app.catalog

import ai.drivemuse.domain.*

/** §17/§20: per-candidate novelty for the selector and ranking. Video-keyed tracks are linked through playable_ref; unlinked ones are UNKNOWN, never "new". */
data class CandidateNovelty(val canonicalTrackId: String?, val novelty: Novelty, val recentExposureCount: Int, val mixClass: MixClass)

class NoveltyAnnotator(private val dao: CatalogDao, private val scope: String = "default") {
    suspend fun annotate(tracks: List<Track>, likedArtists: Set<String>, historyCoverageSince: Long, now: Long = System.currentTimeMillis()): Map<String, CandidateNovelty> {
        if (tracks.isEmpty()) return emptyMap()
        val refs = tracks.mapNotNull { t -> dao.ref("youtube", t.id)?.takeIf { it.matchStatus == MatchStatus.CONFIRMED.name && it.trackId != null }?.let { t.id to it.trackId!! } }.toMap()
        val exp = if (refs.isEmpty()) emptyMap() else dao.experiences(scope, refs.values.distinct()).associateBy { it.trackId }
        return tracks.associate { t ->
            val canonical = refs[t.id]
            val known = t.artist in likedArtists
            val novelty = if (canonical == null) Novelty(NoveltyState.UNKNOWN, listOf("link:none"), historyCoverageSince, known)
                else NoveltyResolver.resolve(exp[canonical]?.domain() ?: TrackExperience(canonical, historyCoverageSince = historyCoverageSince), known, false)
            t.id to CandidateNovelty(canonical, novelty, exp[canonical]?.exposureCount ?: 0, ExplorationMix.classify(novelty.state, known, t.affinity))
        }
    }
}
