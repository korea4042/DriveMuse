package ai.drivemuse.domain
import kotlin.test.*
class DriveDomainTest {
    @Test fun readOnlyScope() { assertEquals("https://www.googleapis.com/auth/youtube.readonly",Policy.SCOPE_READONLY) }
    @Test fun handoffRejectsNonMusic() {
        assertTrue(Policy.playableTrack("10","none",231))
        assertFalse(Policy.playableTrack("24","none",231))          // 비음악 카테고리
        assertFalse(Policy.playableTrack("10","live",231))          // 라이브 스트림
        assertFalse(Policy.playableTrack("10","none",3600))         // 1시간 믹스 업로드
        assertFalse(Policy.playableTrack("10","none",30))           // 인트로/쇼츠
    }
    @Test fun searchCeilingStopsAtLimit() {
        assertTrue(Quota.canSearch(19)); assertFalse(Quota.canSearch(20))
        assertEquals(0,Quota.remainingSearches(25))
    }
    @Test fun manualOverrideBlocks() { assertFalse(Policy.canAutoSelect(true,true,1.0,100,99)); assertTrue(Policy.canAutoSelect(true,true,1.0,100,100)) }
    @Test fun scopedRuleWins() { val global=MusicRule("a",null,"",.2,createdAt=100); val scoped=MusicRule("b",DriveContext.COMMUTE_HOME,"",.4,createdAt=0); assertEquals(.4,RuleEngine.resolve(listOf(global,scoped),DriveContext.COMMUTE_HOME,.5).discovery) }
    @Test fun disabledRuleIgnored() { assertEquals(.5,RuleEngine.resolve(listOf(MusicRule("x",null,"",.2,enabled=false)),DriveContext.TRAVEL,.5).discovery) }
    @Test fun parserRejectsUnknownAndOverRange() { assertNull(RuleEngine.parse("아티스트 다 삭제해", "x",0)); assertNull(RuleEngine.parse("새 노래 101%", "x",0)); assertEquals(.3,RuleEngine.parse("퇴근길 잔잔하게 새 노래 30%","x",0)?.discovery) }
    @Test fun rankerAvoidsConsecutiveArtistAndSkipped() { val rows=listOf(Track("a","a","A"),Track("b","b","A"),Track("c","c","B"),Track("d","d","C",skipped=true)); val chosen=Ranker.select(rows,EffectiveRules(.4,1.0)); assertFalse(chosen.any {it.skipped}); assertTrue(chosen.zipWithNext().all {(a,b)->a.artist!=b.artist}) }
    @Test fun quietRuleAllowsUnknownEnergy() { val rows=listOf(Track("a","a","A"),Track("b","b","B",energy=.4)); assertEquals(setOf("a", "b"),Ranker.select(rows,EffectiveRules(.3,.55)).map {it.id}.toSet()) }
    @Test fun urlIdentifierValidation() { assertTrue(Policy.validTrackId("abcdefghijk")); assertFalse(Policy.validTrackId("https://evil")) }
    @Test fun affinityOutranksFreshnessWithoutProviderSignal() {
        val liked=Track("a","a","A",affinity=.9,freshness=.4)
        val fresh=Track("b","b","B",affinity=.35,freshness=.8)
        assertEquals("a",Ranker.select(listOf(liked,fresh),EffectiveRules(0.0,1.0)).first().id)
    }
}
