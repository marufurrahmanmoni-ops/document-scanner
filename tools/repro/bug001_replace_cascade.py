"""BUG-001 reproduction: Room's generated INSERT OR REPLACE on `documents` (SyncDao.upsertDocument) with
PRAGMA foreign_keys = ON (AppDatabase_Impl.onOpen) cascade-deletes every page of the document.
Uses the exact table SQL Room exported (app/schemas/.../2.json) and the exact statement shape from SyncDao_Impl."""
import json, sqlite3, sys, os
here = os.path.dirname(os.path.abspath(__file__))
schema = json.load(open(os.path.join(here, '../../app/schemas/com.docscanner.app.data.db.AppDatabase/2.json')))
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys = ON')
for e in schema['database']['entities']:
    db.execute(e['createSql'].replace('${TABLE_NAME}', e['tableName']))
cols = [f['columnName'] for f in next(e for e in schema['database']['entities'] if e['tableName']=='documents')['fields']]
doc = dict(id='d1', owner_id='local', folder_id=None, title='Scan', status='ready', page_count=3, cover_page_id=None,
           total_bytes=0, ocr_state='none', is_locked=0, default_filter=None, created_at=1, updated_at=1, opened_at=None,
           deleted_at=None, version=1, server_rev=None, sync_state='local')
db.execute(f"INSERT INTO documents ({','.join(cols)}) VALUES ({','.join('?'*len(cols))})", [doc[c] for c in cols])
pcols = [f['columnName'] for f in next(e for e in schema['database']['entities'] if e['tableName']=='pages')['fields']]
for i in range(3):
    p = {c: None for c in pcols}
    p.update(id=f'p{i}', owner_id='local', document_id='d1', order_key=f'a{i}', original_path='x', original_sha256='x',
             original_width=1, original_height=1, source_rotation=0, source='camera', quad_source='auto', rotation=0,
             filter='auto', brightness=0, contrast=0, recipe_version=1, render_state='done', created_at=1, updated_at=1,
             version=1, sync_state='local')
    db.execute(f"INSERT INTO pages ({','.join(pcols)}) VALUES ({','.join('?'*len(pcols))})", [p[c] for c in pcols])
before = db.execute("SELECT COUNT(*) FROM pages WHERE document_id='d1'").fetchone()[0]
doc['title'] = 'Renamed on another phone'
mode = sys.argv[1] if len(sys.argv) > 1 else 'replace'
if mode == 'replace':   # what @Insert(onConflict = REPLACE) generates
    db.execute(f"INSERT OR REPLACE INTO documents ({','.join(cols)}) VALUES ({','.join('?'*len(cols))})", [doc[c] for c in cols])
else:                    # what @Upsert generates: INSERT, and on conflict UPDATE the existing row
    try:
        db.execute(f"INSERT INTO documents ({','.join(cols)}) VALUES ({','.join('?'*len(cols))})", [doc[c] for c in cols])
    except sqlite3.IntegrityError:
        db.execute(f"UPDATE documents SET {','.join(c+'=?' for c in cols)} WHERE id=?", [doc[c] for c in cols] + ['d1'])
after = db.execute("SELECT COUNT(*) FROM pages WHERE document_id='d1'").fetchone()[0]
title = db.execute("SELECT title FROM documents WHERE id='d1'").fetchone()[0]
print(f"mode={mode} pages before={before} after={after} title={title!r}")
sys.exit(0 if after == before else 1)
