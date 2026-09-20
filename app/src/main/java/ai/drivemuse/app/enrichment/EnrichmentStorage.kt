package ai.drivemuse.app.enrichment

import androidx.room.*
import ai.drivemuse.app.CandidateEntity
import ai.drivemuse.domain.*
import org.json.JSONArray

/*
 * Storage for the independent metadata layer.
 *
 * These are candidate-scoped tables, not the §27 Track layer. That layer keys on an internal UUID
 * and is still empty; putting Spotify ids into `metadata_assertion` would mix two id namespaces in
 * one table and leave the Track layer unusable the day it is woken up. Separate tables also buy the
 * cascade: an assertion is only ever about a row in `candidates`, so it dies with it.
 */

@Entity(
    tableName = "candidate_assertion",
    foreignKeys = [ForeignKey(entity = CandidateEntity::class, parentColumns = ["videoId"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId", "field"), Index("expiresAt")]
)
data class CandidateAssertionEntity(
    @PrimaryKey val assertionId: String, val trackId: String, val field: String, val valueJson: String,
    val basis: String, val source: String, val confidence: Double?, val evidenceIdsJson: String,
    val metadataVersion: Int, val fetchedAt: Long, val expiresAt: Long?
) {
    fun domain() = CandidateAssertion(
        assertionId, trackId, field, valueJson, Basis.valueOf(basis), AssertionSource.valueOf(source),
        confidence, decode(evidenceIdsJson), metadataVersion, fetchedAt, expiresAt
    )

    companion object {
        fun from(a: CandidateAssertion) = CandidateAssertionEntity(
            a.assertionId, a.trackId, a.field, a.value, a.basis.name, a.source.name,
            a.confidence, encode(a.evidenceIds), a.metadataVersion, a.fetchedAt, a.expiresAt
        )

        fun encode(ids: List<String>) = JSONArray(ids).toString()
        fun decode(json: String) = runCatching { JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }
}

@Entity(
    tableName = "enrichment_state",
    foreignKeys = [ForeignKey(entity = CandidateEntity::class, parentColumns = ["videoId"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("nextEligibleAt"), Index("lfStatus")]
)
data class EnrichmentStateEntity(
    @PrimaryKey val trackId: String, val mbStatus: String, val lfStatus: String, val llmStatus: String,
    val metadataVersion: Int, val lastAttemptAt: Long, val nextEligibleAt: Long, val attempts: Int
) {
    fun domain() = EnrichmentState(
        trackId, EnrichStatus.valueOf(mbStatus), EnrichStatus.valueOf(lfStatus), EnrichStatus.valueOf(llmStatus),
        metadataVersion, lastAttemptAt, nextEligibleAt, attempts
    )

    companion object {
        fun from(s: EnrichmentState) = EnrichmentStateEntity(
            s.trackId, s.mbStatus.name, s.lfStatus.name, s.llmStatus.name,
            s.metadataVersion, s.lastAttemptAt, s.nextEligibleAt, s.attempts
        )
    }
}

@Dao
abstract class EnrichmentDao {
    @Query("SELECT * FROM candidate_assertion WHERE trackId = :trackId") abstract suspend fun assertions(trackId: String): List<CandidateAssertionEntity>
    @Query("SELECT * FROM candidate_assertion WHERE trackId IN (:trackIds)") abstract suspend fun assertions(trackIds: List<String>): List<CandidateAssertionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putAssertions(rows: List<CandidateAssertionEntity>)
    @Query("DELETE FROM candidate_assertion WHERE trackId = :trackId AND field IN (:fields) AND basis = :basis") abstract suspend fun clearField(trackId: String, fields: List<String>, basis: String)
    @Query("DELETE FROM candidate_assertion WHERE expiresAt IS NOT NULL AND expiresAt < :cutoff") abstract suspend fun pruneAssertions(cutoff: Long)

    @Query("SELECT * FROM enrichment_state WHERE trackId = :trackId") abstract suspend fun state(trackId: String): EnrichmentStateEntity?
    @Query("SELECT * FROM enrichment_state") abstract suspend fun states(): List<EnrichmentStateEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putState(row: EnrichmentStateEntity)

    /** Coverage for the settings and agent screens (§10). Counted per candidate, never summed with it. */
    @Query("SELECT COUNT(DISTINCT trackId) FROM candidate_assertion WHERE field = 'tag'") abstract suspend fun taggedCount(): Int
    @Query("SELECT COUNT(DISTINCT trackId) FROM candidate_assertion WHERE field IN ('energy','mood')") abstract suspend fun featuredCount(): Int
    @Query("SELECT COUNT(DISTINCT trackId) FROM candidate_assertion WHERE field IN ('energy','mood') AND basis = 'AI_INFERRED'") abstract suspend fun inferredCount(): Int

    /**
     * Replacing a basis's view of a field is one step: the old rows go and the new ones land
     * together, so a reader never sees a field with neither value.
     */
    @Transaction open suspend fun replace(trackId: String, basis: Basis, fields: List<String>, rows: List<CandidateAssertion>) {
        clearField(trackId, fields, basis.name)
        if (rows.isNotEmpty()) putAssertions(rows.map { CandidateAssertionEntity.from(it) })
    }
}
