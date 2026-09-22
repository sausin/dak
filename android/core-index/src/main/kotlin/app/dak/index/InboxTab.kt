package app.dak.index

/** Inbox tabs shown by the conversation list, in display order. */
enum class InboxTab {
    /** Everything except spam and archived conversations. */
    ALL,
    PERSONAL,
    TRANSACTION,
    OTP,
    PROMOTION,
    SPAM,

    /** Conversations the user archived, plus conversations holding individually archived messages. */
    ARCHIVED,

    /** Starred conversations plus conversations holding starred messages. */
    STARRED,
}

/** Result ordering for [app.dak.index.repo.SearchRepository.search]. */
enum class SearchSort {
    /** Most recent matching message first. */
    RECENT,

    /** Conversations with the most matching messages first, then most recent. */
    RELEVANCE,

    /** Largest transaction amount (minor units, as written in the SMS) first. */
    AMOUNT,
}
