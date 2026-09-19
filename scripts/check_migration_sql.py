from pathlib import Path
import re, sqlite3
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/ai/drivemuse/app/Storage.kt').read_text()
db=sqlite3.connect(':memory:')
db.execute('CREATE TABLE rules (id TEXT PRIMARY KEY, text TEXT)')
db.execute('CREATE TABLE history (id TEXT PRIMARY KEY, title TEXT)')
db.execute("CREATE TABLE played (rowId INTEGER PRIMARY KEY AUTOINCREMENT, videoId TEXT, playedAt INTEGER)")
db.execute("INSERT INTO rules VALUES ('existing','keep')")
db.execute("INSERT INTO history VALUES ('existing','keep')")
statements=re.findall(r'db.execSQL\("([^"\n]+)"\)',s)
for statement in statements:
    if statement.startswith('CREATE TABLE IF NOT EXISTS `candidates`'):
        db.execute(statement); db.execute("INSERT INTO candidates VALUES ('abcdefghijk','Blue Hour','Northbound',210,'',0,.5,.5,NULL,'chart',1000)"); db.execute("INSERT INTO played (videoId, playedAt) VALUES ('abcdefghijk', 5)")
    else: db.execute(statement)
assert db.execute('SELECT text FROM rules').fetchone()==('keep',)
assert db.execute('SELECT title FROM history').fetchone()==('keep',)
assert db.execute('SELECT count(*) FROM outcomes').fetchone()==(0,)
assert 'audioLanguage' in [r[1] for r in db.execute('PRAGMA table_info(candidates)')]
row=('id','session',1,1,1,1,'candidate','READY','[]',0)
db.execute('INSERT INTO batches VALUES (?,?,?,?,?,?,?,?,?,?)',row)
try:
 db.execute('INSERT INTO batches VALUES (?,?,?,?,?,?,?,?,?,?)',('different',)+row[1:])
 raise AssertionError('duplicate generation accepted')
except sqlite3.IntegrityError: pass
# v2.3 §18/§27: legacy videos become UNMATCHED staging refs + PENDING discovery items, never VALIDATED tracks; played is not listening evidence.
assert db.execute("SELECT matchStatus, trackId FROM playable_ref WHERE resourceId='abcdefghijk'").fetchone()==('UNMATCHED',None)
assert db.execute("SELECT queueStatus FROM discovery_item WHERE rawRef='abcdefghijk'").fetchone()==('PENDING',)
assert db.execute('SELECT count(*) FROM track').fetchone()==(0,)
assert db.execute('SELECT count(*) FROM track_experience').fetchone()==(0,)
assert db.execute('SELECT count(*) FROM played').fetchone()==(1,)
assert db.execute("SELECT generation FROM collection_control WHERE scope='default'").fetchone()==(1,)
for t in ['track','track_identifier','playable_ref','metadata_assertion','discovery_item','enrichment_job','validation_decision','identity_alias','track_experience','user_track_context','discovery_seed','collection_run','collection_control','quota_ledger','integration_config']:
    assert db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?",(t,)).fetchone(), t
db.execute("INSERT INTO track_identifier VALUES ('t1','ISRC','KR1',\"MB\",0)"); db.execute("INSERT INTO track_identifier VALUES ('t2','ISRC','KR1','MB',0)")  # ISRC not globally unique (§27)
# Phase 1 §9: observation tables exist and an explicit rating written before v5 survives with neutral defaults.
db.execute("INSERT INTO outcomes (attemptId,trackId,sessionId,version,score,explicit,createdAt) VALUES ('legacy','t','s',1,1.0,1,10)")
kept=db.execute("SELECT score, explicit, activeMs, coveredMs, ratio, uncertain, endReason, confidence FROM outcomes WHERE attemptId='legacy'").fetchone()
assert kept==(1.0,1,None,None,None,None,None,None), kept
for t in ['playback_attempt','playback_event']:
    assert db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?",(t,)).fetchone(), t
db.execute("INSERT INTO playback_attempt VALUES ('a1','t','s',NULL,0,NULL,0,NULL,NULL,'COMMANDED')")
db.execute("INSERT INTO playback_event VALUES ('e1','a1',0,0,210000,0,'APP_REMOTE')")
assert db.execute("SELECT count(*) FROM playback_event WHERE attemptId='a1'").fetchone()==(1,)
# Phase 1 §5: the genre cache exists and candidates gained the approximation columns without losing a row.
assert db.execute("SELECT count(*) FROM candidates WHERE videoId='abcdefghijk'").fetchone()==(1,)
cols=[r[1] for r in db.execute('PRAGMA table_info(candidates)')]
for c in ['energyHint','energyBasis','artistIds']: assert c in cols, c
db.execute("INSERT INTO artist_genre_cache VALUES ('a1','[\"k-pop\"]',0)")
assert db.execute("SELECT genresJson FROM artist_genre_cache WHERE artistId='a1'").fetchone()==('["k-pop"]',)
print('PASS: additive migrations v1→v6 preserve rows, stage legacy videos as UNMATCHED, keep played as exposure only, add observation tables and genre columns')
