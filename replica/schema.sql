-- ============================================================================
-- Scanner: local database schema (SQLite via Room, encrypted with SQLCipher)
-- Version 1. Source of truth for Room @Entity classes in :core:database.
-- Room generates the real DDL. This file is the reviewed contract, and
-- migration tests check the generated schema JSON against it.
--
-- Conventions (adapted from replica-architect for an offline-first SQLite app):
--   id          TEXT uuid v7 (time-ordered), generated on device, never reused.
--               Same id locally and on a future server, so sync never remaps keys.
--   owner_id    TEXT, 'local' until the user signs in. Then a one-shot
--               migration rewrites it to the account id. Every user-owned table has it.
--   times       INTEGER epoch milliseconds, UTC. (SQLite has no timestamptz.)
--   deleted_at  soft delete = tombstone. Needed for Recently deleted (30 days)
--               and so a future sync can propagate deletes. Purged by a job.
--   version     INTEGER, +1 on every local write. A future sync uses it for
--               optimistic concurrency. server_rev holds the last server revision seen.
--   enums       TEXT + CHECK constraint (readable, migration-friendly).
--   files       Never stored in the DB. Rows hold a relative path into the encrypted
--               blob store and a SHA-256 content hash (dedupe and future upload).
--   access      Single local user: no row-level security. The data layer is
--               the only writer (repositories), and every query filters
--               deleted_at IS NULL unless it is a trash or sync query. When accounts
--               arrive, the repositories add `owner_id = :currentOwner` to every query.
-- ============================================================================

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- folders: a tree. parent_id NULL = root.
-- ---------------------------------------------------------------------------
CREATE TABLE folders (
  id            TEXT    PRIMARY KEY NOT NULL,
  owner_id      TEXT    NOT NULL DEFAULT 'local',
  parent_id     TEXT    REFERENCES folders(id) ON DELETE RESTRICT,
                -- RESTRICT: deleting a folder is an app-level operation
                -- (move children to trash first). It is never a silent cascade.
  name          TEXT    NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
  sort_order    INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  deleted_at    INTEGER,
  version       INTEGER NOT NULL DEFAULT 1,
  server_rev    INTEGER,
  sync_state    TEXT    NOT NULL DEFAULT 'local'
                CHECK (sync_state IN ('local','pending','synced','conflict')),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX idx_folders_parent ON folders(parent_id);
CREATE INDEX idx_folders_owner  ON folders(owner_id, deleted_at);
-- Two live folders with the same name in the same parent are not allowed (case-insensitive).
CREATE UNIQUE INDEX uq_folders_name_in_parent
  ON folders(owner_id, ifnull(parent_id, ''), name COLLATE NOCASE)
  WHERE deleted_at IS NULL;
-- Cycle prevention (A inside B inside A) is checked in FolderRepository.move()
-- with a recursive CTE inside the same transaction. SQLite cannot express it as a constraint.

-- ---------------------------------------------------------------------------
-- documents
-- ---------------------------------------------------------------------------
CREATE TABLE documents (
  id              TEXT    PRIMARY KEY NOT NULL,
  owner_id        TEXT    NOT NULL DEFAULT 'local',
  folder_id       TEXT    REFERENCES folders(id) ON DELETE SET NULL,
                  -- SET NULL: if a folder row is ever hard-deleted, its docs fall to the root, never vanish.
  title           TEXT    NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 200),
  status          TEXT    NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft','ready')),
                  -- draft = scan session not saved yet (survives process death, S11 resume banner)
  page_count      INTEGER NOT NULL DEFAULT 0 CHECK (page_count >= 0),
                  -- denormalised for list speed, maintained by PageRepository in the same transaction
  cover_page_id   TEXT,   -- no FK (circular). Validated by the repository
  total_bytes     INTEGER NOT NULL DEFAULT 0,  -- sum of page blob sizes, for sort-by-size
  ocr_state       TEXT    NOT NULL DEFAULT 'none'
                  CHECK (ocr_state IN ('none','queued','running','partial','done','failed')),
  is_locked       INTEGER NOT NULL DEFAULT 0 CHECK (is_locked IN (0,1)),
                  -- locked = needs app-lock auth to open, thumbnails blurred.
                  -- Export passwords are per export, not stored.
  default_filter  TEXT    CHECK (default_filter IS NULL OR default_filter IN
                  ('original','auto','clean','gray','bw','bw_soft')),
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  opened_at       INTEGER,          -- "Recent" on Home sorts by this, falling back to updated_at
  deleted_at      INTEGER,
  version         INTEGER NOT NULL DEFAULT 1,
  server_rev      INTEGER,
  sync_state      TEXT    NOT NULL DEFAULT 'local'
                  CHECK (sync_state IN ('local','pending','synced','conflict'))
);
CREATE INDEX idx_documents_folder   ON documents(folder_id);
CREATE INDEX idx_documents_list     ON documents(owner_id, deleted_at, status, updated_at DESC);
CREATE INDEX idx_documents_recent   ON documents(owner_id, deleted_at, opened_at DESC);
CREATE INDEX idx_documents_title    ON documents(owner_id, title COLLATE NOCASE);
CREATE INDEX idx_documents_size     ON documents(owner_id, total_bytes);
CREATE INDEX idx_documents_trash    ON documents(deleted_at) WHERE deleted_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- pages: non-destructive. The original capture is immutable. Everything else is
-- a recipe (quad + rotation + filter + adjustments) that re-renders processed_path.
-- ---------------------------------------------------------------------------
CREATE TABLE pages (
  id               TEXT    PRIMARY KEY NOT NULL,
  owner_id         TEXT    NOT NULL DEFAULT 'local',
  document_id      TEXT    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                   -- CASCADE: only on a hard purge of the document (trash job). App deletes are soft.
  order_key        TEXT    NOT NULL,
                   -- fractional index (e.g. "a0", "a0V", "a1"). Moving a page rewrites ONE row,
                   -- and two devices reordering later merge without renumbering.
  -- immutable source
  original_path    TEXT    NOT NULL,   -- relative path in BlobStore (encrypted JPEG)
  original_sha256  TEXT    NOT NULL CHECK (length(original_sha256) = 64),
  original_width   INTEGER NOT NULL CHECK (original_width > 0),
  original_height  INTEGER NOT NULL CHECK (original_height > 0),
  source_rotation  INTEGER NOT NULL DEFAULT 0 CHECK (source_rotation IN (0,90,180,270)),
                   -- sensor rotation of the stored JPEG (CameraX rotationDegrees). The quad is in upright space.
  source           TEXT    NOT NULL CHECK (source IN ('camera','gallery','pdf_import')),
  -- edit recipe
  quad             TEXT,
                   -- JSON [[x,y]x4] normalised 0..1 in original-image space, clockwise from top-left.
                   -- NULL = full frame.
  quad_source      TEXT    NOT NULL DEFAULT 'auto' CHECK (quad_source IN ('auto','manual','full')),
  rotation         INTEGER NOT NULL DEFAULT 0 CHECK (rotation IN (0,90,180,270)),
  filter           TEXT    NOT NULL DEFAULT 'auto'
                   CHECK (filter IN ('original','auto','clean','gray','bw','bw_soft')),
  brightness       INTEGER NOT NULL DEFAULT 0 CHECK (brightness BETWEEN -100 AND 100),
  contrast         INTEGER NOT NULL DEFAULT 0 CHECK (contrast BETWEEN -100 AND 100),
  recipe_version   INTEGER NOT NULL DEFAULT 1,
                   -- bump when the imaging pipeline changes output, so stale renders get rebuilt
  -- derived (cache, rebuildable from source + recipe)
  processed_path   TEXT,               -- encrypted JPEG, NULL until rendered
  processed_sha256 TEXT,
  processed_width  INTEGER,
  processed_height INTEGER,
  processed_bytes  INTEGER,
  render_state     TEXT    NOT NULL DEFAULT 'pending'
                   CHECK (render_state IN ('pending','rendering','done','failed')),
  thumb_path       TEXT,               -- encrypted WebP, 512px long edge
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  deleted_at       INTEGER,
  version          INTEGER NOT NULL DEFAULT 1,
  server_rev       INTEGER,
  sync_state       TEXT    NOT NULL DEFAULT 'local'
                   CHECK (sync_state IN ('local','pending','synced','conflict'))
);
CREATE INDEX idx_pages_document ON pages(document_id);
-- Order is unique among live pages of a document: a reorder bug becomes a constraint error, not a lost page.
CREATE UNIQUE INDEX uq_pages_order ON pages(document_id, order_key) WHERE deleted_at IS NULL;
CREATE INDEX idx_pages_render   ON pages(render_state) WHERE render_state <> 'done';
CREATE INDEX idx_pages_sha      ON pages(original_sha256);

-- ---------------------------------------------------------------------------
-- page_ocr: one row per page per OCR run (only the latest is kept live).
-- ---------------------------------------------------------------------------
CREATE TABLE page_ocr (
  page_id        TEXT    PRIMARY KEY NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  owner_id       TEXT    NOT NULL DEFAULT 'local',
  state          TEXT    NOT NULL DEFAULT 'queued'
                 CHECK (state IN ('queued','running','done','failed','empty')),
  languages      TEXT    NOT NULL DEFAULT 'eng'
                 CHECK (languages IN ('eng','ben','eng+ben')),
  detected_script TEXT   CHECK (detected_script IS NULL OR detected_script IN ('latin','bengali','mixed')),
  text           TEXT,   -- NFC-normalised full text, page order
  mean_confidence INTEGER CHECK (mean_confidence IS NULL OR mean_confidence BETWEEN 0 AND 100),
  words_path     TEXT,   -- encrypted JSON blob of word boxes [{t,x,y,w,h,c}] for highlights and the text layer
  engine         TEXT    NOT NULL DEFAULT 'tesseract',
  engine_version TEXT,   -- e.g. "5.5.x+tessdata_best-4.1". Re-OCR when the model changes
  recipe_version INTEGER NOT NULL,  -- pages.recipe_version this ran on. Stale if different
  error          TEXT,
  attempts       INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);
CREATE INDEX idx_page_ocr_state ON page_ocr(state);

-- Full-text index over OCR text and titles. FTS4 external-content table
-- (Room @Fts4(contentEntity = PageOcr)). unicode61 with remove_diacritics=0 so
-- Bengali vowel signs (matras) are not stripped. Queries are NFC-normalised too.
CREATE VIRTUAL TABLE page_ocr_fts USING fts4(
  content="page_ocr",
  text,
  tokenize=unicode61 "remove_diacritics=0"
);
-- Room generates the sync triggers for external-content FTS (insert/update/delete).
-- Title search uses idx_documents_title (prefix LIKE). Titles are short.

-- ---------------------------------------------------------------------------
-- tags (could-have, in the schema now so v1 does not need a migration later)
-- ---------------------------------------------------------------------------
CREATE TABLE tags (
  id          TEXT    PRIMARY KEY NOT NULL,
  owner_id    TEXT    NOT NULL DEFAULT 'local',
  name        TEXT    NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 40),
  color_role  TEXT,   -- a design token role name, never a hex value
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  deleted_at  INTEGER,
  version     INTEGER NOT NULL DEFAULT 1,
  server_rev  INTEGER,
  sync_state  TEXT    NOT NULL DEFAULT 'local'
              CHECK (sync_state IN ('local','pending','synced','conflict'))
);
CREATE UNIQUE INDEX uq_tags_name ON tags(owner_id, name COLLATE NOCASE) WHERE deleted_at IS NULL;

CREATE TABLE document_tags (
  document_id TEXT    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  tag_id      TEXT    NOT NULL REFERENCES tags(id)      ON DELETE CASCADE,
  owner_id    TEXT    NOT NULL DEFAULT 'local',
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (document_id, tag_id)
);
CREATE INDEX idx_document_tags_tag ON document_tags(tag_id);

-- ---------------------------------------------------------------------------
-- sync_outbox: every user-visible write appends a change here in the SAME
-- transaction (transactional outbox). In v1 nothing reads it except the trash
-- and compaction jobs. When cloud sync ships, SyncWorker drains it in order. No
-- table needs to change, so the sync feature is additive.
-- ---------------------------------------------------------------------------
CREATE TABLE sync_outbox (
  seq          INTEGER PRIMARY KEY AUTOINCREMENT,   -- strict local order
  owner_id     TEXT    NOT NULL DEFAULT 'local',
  entity       TEXT    NOT NULL CHECK (entity IN ('folder','document','page','page_ocr','tag','document_tag')),
  entity_id    TEXT    NOT NULL,
  op           TEXT    NOT NULL CHECK (op IN ('upsert','delete','blob')),
  entity_version INTEGER NOT NULL,
  changed_fields TEXT,  -- JSON list of field names, for field-level merges later
  blob_sha256  TEXT,    -- for op='blob': which file to upload
  created_at   INTEGER NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0,
  last_error   TEXT
);
CREATE INDEX idx_outbox_entity ON sync_outbox(entity, entity_id);
-- Idempotency: the server dedupes on (entity, entity_id, entity_version), so a retried push is harmless.
-- Compaction: OutboxCompactor keeps only the newest upsert per (entity, entity_id) while the user is offline and signed out.

-- ---------------------------------------------------------------------------
-- Not in the database (deliberately):
--   settings            -> Proto DataStore (typed, observable, one file)
--   app-lock PIN hash   -> Proto DataStore, PBKDF2-HMAC-SHA256 + salt, file encrypted with Tink
--   DB passphrase       -> random 32 bytes, wrapped by an Android Keystore AES key
--   images, PDFs        -> BlobStore (Tink streaming AEAD files under filesDir/blobs)
--   export files        -> cacheDir/exports, deleted after share or after 1h
--   OCR model files     -> filesDir/tessdata (public models, not secret, not encrypted)
-- ---------------------------------------------------------------------------
