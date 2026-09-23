package app.dak.index.sync

/** Pure cursor arithmetic of the stage-2 backfill ("messages strictly older than cursor"). */
object BackfillCursor {
    /**
     * Next cursor after a batch whose oldest message is at [oldestInBatch]. Normally `oldest + 1`, so messages
     * sharing that timestamp but cut off by the batch limit are fetched again (upserts are idempotent); if that
     * would not move the cursor (the whole batch shares the timestamp just below it), step past it.
     */
    fun next(previous: Long, oldestInBatch: Long): Long =
        if (oldestInBatch + 1 < previous) oldestInBatch + 1 else oldestInBatch
}
