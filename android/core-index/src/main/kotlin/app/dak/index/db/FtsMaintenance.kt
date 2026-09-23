package app.dak.index.db

import android.util.Log
import app.dak.index.sql.Tables

/**
 * Housekeeping for the FTS4 index ([Tables.MESSAGE_FTS]).
 *
 * While rows are inserted, FTS4 keeps adding small b-tree segments and merges them level by level (its classic
 * merge; the incremental `automerge` option stays at its default, off, so inserts do no extra merge work). After a
 * bulk load (the stage-2 backfill or a re-index) the index is left as many segments, and every MATCH query has to
 * visit each of them. [optimize] merges them into one, once, at the end of the load: queries get faster and the
 * file smaller. It rewrites the whole FTS index, so it is never run per batch or per incoming message.
 */
object FtsMaintenance {
    private const val TAG = "DakIndex"

    /** FTS4's special command column: `INSERT INTO fts(fts) VALUES('optimize')`. */
    const val OPTIMIZE_SQL: String = "INSERT INTO ${Tables.MESSAGE_FTS}(${Tables.MESSAGE_FTS}) VALUES('optimize')"

    /** Merges the FTS segments into one. Blocking database I/O: call from a background dispatcher. Never throws. */
    fun optimize(db: DakIndexDatabase) {
        try {
            db.openHelper.writableDatabase.execSQL(OPTIMIZE_SQL)
        } catch (e: RuntimeException) {
            Log.w(TAG, "FTS optimize failed: ${e.javaClass.simpleName}")
        }
    }
}
