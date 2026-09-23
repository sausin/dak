package app.dak.backup.format

/**
 * Decides which backed-up automation runs to insert on restore. Pure, so the rules are unit-tested:
 * - rows already present (same [AutomationRunRecord.dedupeKey]) are skipped, so restoring twice adds nothing;
 * - rows missing a rule id, action kind or outcome, dated before 0 or later than `now` plus a day are dropped (a
 *   future-dated row would count toward per-rule counters such as the "rule has sent N messages" reminder);
 * - every string is cut to [ArchiveLimits.MAX_AUTOMATION_RUN_FIELD_CHARS];
 * - at most [ArchiveLimits.MAX_AUTOMATION_RUNS] rows, keeping the newest;
 * - returned oldest first, the order they were originally written.
 *
 * The result is only ever inserted into the run log. Restoring history never creates, enables or changes a rule and
 * never sends anything: rules are not part of the backup, and the caller inserts rows directly into the log store.
 */
object AutomationRunRestore {
    fun plan(
        incoming: List<AutomationRunRecord>,
        existingKeys: Set<String>,
        nowMillis: Long,
    ): List<AutomationRunRecord> {
        val latestAllowed = nowMillis + ArchiveLimits.MAX_AUTOMATION_RUN_CLOCK_SKEW_MILLIS
        val seen = HashSet<String>(existingKeys)
        val accepted = ArrayList<AutomationRunRecord>()
        for (raw in incoming) {
            if (raw.ruleId.isBlank() || raw.actionKind.isBlank() || raw.outcome.isBlank()) continue
            if (raw.atMillis < 0 || raw.atMillis > latestAllowed) continue
            val row = raw.bounded()
            if (!seen.add(row.dedupeKey())) continue
            accepted += row
        }
        accepted.sortWith(compareBy<AutomationRunRecord> { it.atMillis }.thenBy { it.ruleId })
        return if (accepted.size <= ArchiveLimits.MAX_AUTOMATION_RUNS) {
            accepted
        } else {
            accepted.subList(accepted.size - ArchiveLimits.MAX_AUTOMATION_RUNS, accepted.size).toList()
        }
    }
}
