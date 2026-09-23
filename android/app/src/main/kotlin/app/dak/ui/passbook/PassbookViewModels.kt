package app.dak.ui.passbook

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.core.model.MessageKey
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import app.dak.finance.passbook.MonthlyTotal
import app.dak.index.MessageItem
import app.dak.index.repo.AccountAliasSuggestion
import app.dak.index.repo.AccountSummary
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.LedgerRepository
import app.dak.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Passbook: every account and card inferred from bank SMS, with its honest balance, plus "is this the same
 * account?" cards when a bank changed how much of the number it shows (never merged without the user's answer).
 */
@HiltViewModel
class PassbookViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {
    /** Null while loading. */
    val accounts: StateFlow<List<AccountSummary>?> = ledger.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Pending same-account questions, best first. */
    val aliasSuggestions: StateFlow<List<AccountAliasSuggestion>> = ledger.aliasSuggestions()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventChannel = Channel<Unit>(Channel.BUFFERED)

    /** Emits after a confirmed merge (for a snackbar). */
    val merged: Flow<Unit> = eventChannel.receiveAsFlow()

    /** A sample SMS of a suggestion, live. */
    fun sample(key: MessageKey?): Flow<MessageItem?> = key?.let { conversations.message(it) } ?: flowOf(null)

    fun confirmSame(suggestion: AccountAliasSuggestion) {
        viewModelScope.launch {
            if (runCatching { ledger.confirmSameAccount(suggestion.a.account.id, suggestion.b.account.id) }.isSuccess) eventChannel.trySend(Unit)
        }
    }

    fun confirmDifferent(suggestion: AccountAliasSuggestion) {
        viewModelScope.launch { runCatching { ledger.confirmDifferentAccounts(suggestion.a.account.id, suggestion.b.account.id) } }
    }
}

/**
 * One account or card: balance ("unknown since …" rather than a guess), card outstanding for the current billing
 * cycle, monthly totals, and the ledger with each entry's source SMS one tap away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val ledger: LedgerRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    val accountId: String = Uri.decode(savedState.get<String>(Routes.ARG_ACCOUNT_ID).orEmpty())

    val account: StateFlow<AccountSummary?> = ledger.account(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val entries: StateFlow<List<LedgerEntry>?> = ledger.entries(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val outstanding: StateFlow<Money?> = ledger.cardOutstanding(accountId, System.currentTimeMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monthly: StateFlow<List<MonthlyTotal>> = ledger.monthlyTotals(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The raw SMS behind [entry], live (null while not indexed). */
    fun source(entry: LedgerEntry): Flow<MessageItem?> =
        ledger.messageKeyOf(entry)?.let { conversations.message(it) } ?: flowOf(null)

    /** Route to the entry's message in its thread (highlighted), or null when it is no longer indexed. */
    suspend fun routeTo(entry: LedgerEntry): String? {
        val key: MessageKey = ledger.messageKeyOf(entry) ?: return null
        val conversationId = conversations.conversationIdOf(key) ?: return null
        return Routes.conversation(conversationId, highlight = key.toString())
    }

    fun setStatementDay(day: Int?) {
        viewModelScope.launch { runCatching { ledger.setStatementDay(accountId, day) } }
    }

    /** Raw account ids merged into this account (other formats of its number), live. */
    val mergedIds: StateFlow<List<String>> = ledger.canonicalId(accountId)
        .flatMapLatest { ledger.mergedInto(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Accounts of the same bank and kind this one can be merged with by hand. */
    val mergeCandidates: StateFlow<List<AccountSummary>> = ledger.canonicalId(accountId)
        .flatMapLatest { ledger.mergeCandidates(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Merge with…": [other] becomes part of this account (the id showing more digits is kept). */
    fun mergeWith(other: AccountSummary) {
        viewModelScope.launch {
            val self = account.value?.account?.id ?: accountId
            runCatching { ledger.confirmSameAccount(self, other.account.id) }
        }
    }

    /** "Unmerge": [aliasId] becomes its own account again. */
    fun unmerge(aliasId: String) {
        viewModelScope.launch { runCatching { ledger.unmergeAccount(aliasId) } }
    }
}
