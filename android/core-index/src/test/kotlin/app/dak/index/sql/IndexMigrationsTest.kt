package app.dak.index.sql

import app.dak.index.db.IndexMigrations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** String-level checks of the 1 -> 2 migration (the Robolectric test compares it with Room's own schema). */
class IndexMigrationsTest {

    @Test
    fun migrationIsAdditiveOnly() {
        val sql = IndexMigrations.SQL_1_2
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
        assertEquals(1, IndexMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, IndexMigrations.MIGRATION_1_2.endVersion)
    }

    @Test
    fun newIndexUsesRoomsNamingScheme() {
        assertTrue(
            IndexMigrations.SQL_1_2.contains(
                "CREATE INDEX IF NOT EXISTS `index_indexed_message_repeatGroup` ON `indexed_message` (`repeatGroup`)",
            ),
        )
    }
}
