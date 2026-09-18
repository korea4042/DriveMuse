package ai.drivemuse.domain
import kotlin.test.*
class FeatureLearnerTest {
    private fun row(id: String="a",session: String="s",version: Long=1,explicit: Int?=null)=FeatureContribution(Outcome(id,"track",session,version,ListeningTotals(100000,100000,1.0,false),EndReason.NATURAL_END,1.0,explicit,0),DriveContext.COMMUTE_HOME,listOf(VerifiedFeature("genre","JAZZ","YOUTUBE_TOPIC",.9),VerifiedFeature("mood","CALM","invented",.2)))
    @Test fun unsupportedFeaturesNeverPropagate() { val result=FeatureLearner.summarize(listOf(row()),"s",DriveContext.COMMUTE_HOME,0);assertEquals(setOf("track","genre"),result.map { it.axis }.toSet()) }
    @Test fun longTermRequiresThreeDistinctSessions() { assertEquals(0.0,FeatureLearner.summarize(listOf(row(),row("b")),"s",DriveContext.COMMUTE_HOME,0).first().longTermScore);assertTrue(FeatureLearner.summarize(listOf(row(),row("b","s2"),row("c","s3")),"s",DriveContext.COMMUTE_HOME,0).first().longTermScore>0) }
    @Test fun revisedOutcomeReplacesContribution() { val result=FeatureLearner.summarize(listOf(row(explicit=1),row(version=2,explicit=-1)),"s",DriveContext.COMMUTE_HOME,0);val track=result.single { it.axis=="track" };assertEquals(-.2,track.sessionScore);assertEquals(1,track.supportCount);assertEquals(-.05,result.single { it.axis=="genre" }.sessionScore) }
    @Test fun contextDecayAndDailyCap() { val rows=(1..20).map { row(it.toString(),explicit=1) };val now=14L*86400000;val value=FeatureLearner.summarize(rows,"s",DriveContext.COMMUTE_HOME,now).single { it.axis=="track" };assertEquals(.025,value.contextScore);assertTrue(value.longTermScore<=.02) }
}
