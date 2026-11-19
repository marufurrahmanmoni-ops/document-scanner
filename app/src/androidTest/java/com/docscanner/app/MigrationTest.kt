package com.docscanner.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.docscanner.app.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrading from v1 keeps every document and page (users of the first build must not lose scans). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1ToV2KeepsDocumentsAndPages() {
        helper.createDatabase("m.db", 1).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, owner_id, folder_id, title, status, page_count, cover_page_id, total_bytes, ocr_state, " +
                    "is_locked, default_filter, created_at, updated_at, opened_at, deleted_at, version, server_rev, sync_state) " +
                    "VALUES ('d1','local',NULL,'Lease','READY',1,NULL,0,'NONE',0,'AUTO',1,1,NULL,NULL,1,0,'LOCAL')",
            )
        }
        val db = helper.runMigrationsAndValidate("m.db", 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM documents").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM sync_shadow").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
}
