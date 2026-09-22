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
import app.dak.index.repo.AccountSummary
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.LedgerRepository
import app.dak.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Passbook: every account and card inferred from bank SMS, with its honest balance. */
@HiltViewModel
class PassbookViewModel @Inject constructor(ledger: LedgerRepository) : ViewModel() {
    /** Null while loading. */
    val accounts: StateFlow<List<AccountSummary>?> = ledger.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * One account or card: balance ("unknown since …" rather than a guess), card outstanding for the current billing
 * cycle, monthly totals, and the ledger with each entry's source SMS one tap away.
 */
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
}
