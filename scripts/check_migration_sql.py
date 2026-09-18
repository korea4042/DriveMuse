from pathlib import Path
import re, sqlite3
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/ai/drivemuse/app/Storage.kt').read_text()
db=sqlite3.connect(':memory:')
db.execute('CREATE TABLE rules (id TEXT PRIMARY KEY, text TEXT)')
db.execute('CREATE TABLE history (id TEXT PRIMARY KEY, title TEXT)')
db.execute("INSERT INTO rules VALUES ('existing','keep')")
db.execute("INSERT INTO history VALUES ('existing','keep')")
for statement in re.findall(r'db.execSQL\("([^"\n]+)"\)',s): db.execute(statement)
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
print('PASS: additive migrations preserve existing rows and enforce unique batch generation')
