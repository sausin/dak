package app.dak.ui.selftest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.R
import app.dak.core.model.SimInfo
import app.dak.notifications.NotificationChannels
import app.dak.notifications.ReliabilityChecker
import app.dak.notifications.ReliabilityReport
import app.dak.notifications.SelfTestMonitor
import app.dak.telephony.MessageSender
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/** State of the self-test screen. */
data class SelfTestUiState(
    val report: ReliabilityReport = ReliabilityReport(emptyList()),
    val sims: List<SimInfo> = emptyList(),
    val selectedSubId: Int? = null,
    val number: String = "",
    val sms: SmsTestState = SmsTestState.Idle,
    val localNotificationPosted: Boolean? = null,
)

sealed interface SmsTestState {
    data object Idle : SmsTestState
    data object Sending : SmsTestState
    data object Waiting : SmsTestState
    data class Received(val seconds: Long, val notificationPosted: Boolean) : SmsTestState
    data class Failed(val reason: String) : SmsTestState
    /** Sent, but nothing came back within the timeout (some carriers delay or drop self-addressed SMS). */
    data object TimedOut : SmsTestState
}

@HiltViewModel
class SelfTestViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: ReliabilityChecker,
    private val sims: SimRepository,
    private val sender: MessageSender,
    private val monitor: SelfTestMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(SelfTestUiState())
    val state: StateFlow<SelfTestUiState> = _state.asStateFlow()
    private var waitJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            sims.sims.collect { list ->
                _state.update { s ->
                    val selected = s.selectedSubId?.takeIf { id -> list.any { it.subId == id } }
                        ?: list.firstOrNull { it.subId == sims.defaultSmsSubId() }?.subId
                        ?: list.firstOrNull()?.subId
                    s.copy(sims = list, selectedSubId = selected, number = s.number.ifBlank { ownNumber(selected, list).orEmpty() })
                }
            }
        }
        viewModelScope.launch {
            monitor.status.collect { status ->
                if (status is SelfTestMonitor.State.Received) {
                    waitJob?.cancel()
                    _state.update { it.copy(sms = SmsTestState.Received(status.roundTripMillis / 1000, status.notificationPosted)) }
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(report = checker.check()) }
    }

    fun selectSim(subId: Int) {
        _state.update { it.copy(selectedSubId = subId, number = ownNumber(subId, it.sims) ?: it.number) }
    }

    fun onNumberChange(value: String) {
        _state.update { it.copy(number = value) }
    }

    /** Posts a notification on the self-test channel right now (checks the posting path without the network). */
    @SuppressLint("MissingPermission")
    fun postLocalTest() {
        val manager = NotificationManagerCompat.from(context)
        val posted = if (!manager.areNotificationsEnabled()) false else try {
            manager.notify(
                LOCAL_TEST_ID,
                NotificationCompat.Builder(context, NotificationChannels.SELF_TEST)
                    .setSmallIcon(R.drawable.ic_stat_dak)
                    .setContentTitle(context.getString(R.string.selftest_local_title))
                    .setContentText(context.getString(R.string.selftest_local_body))
                    .setAutoCancel(true)
                    .build(),
            )
            true
        } catch (e: SecurityException) {
            false
        }
        _state.update { it.copy(localNotificationPosted = posted) }
    }

    /** Sends a test SMS to the user's own number on the chosen SIM and waits for it to come back. */
    fun sendTestSms() {
        val s = _state.value
        val number = s.number.trim()
        val subId = s.selectedSubId ?: sims.defaultSmsSubId()
        if (number.isEmpty()) return
        val token = "#" + Random.nextInt(1000, 9999)
        val body = monitor.arm(token)
        _state.update { it.copy(sms = SmsTestState.Sending) }
        viewModelScope.launch {
            val result = runCatching {
                sender.sendSms(OutgoingSms(addresses = listOf(number), body = body, subId = subId, requestDeliveryReport = false))
            }.getOrElse { SendResult.Failed(it.message ?: it.javaClass.simpleName) }
            when (result) {
                is SendResult.Queued -> {
                    _state.update { if (it.sms is SmsTestState.Sending) it.copy(sms = SmsTestState.Waiting) else it }
                    waitJob = launch {
                        delay(TIMEOUT_MILLIS)
                        _state.update { if (it.sms is SmsTestState.Waiting) it.copy(sms = SmsTestState.TimedOut) else it }
                    }
                }
                is SendResult.Failed -> {
                    monitor.reset()
                    _state.update { it.copy(sms = SmsTestState.Failed(result.reason)) }
                }
            }
        }
    }

    override fun onCleared() {
        monitor.reset()
    }

    /** The SIM's own number when the system knows it (needs READ_PHONE_NUMBERS on 13+). */
    @SuppressLint("MissingPermission")
    private fun ownNumber(subId: Int?, list: List<SimInfo>): String? {
        subId ?: return null
        list.firstOrNull { it.subId == subId }?.number?.takeIf { it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
        return try {
            manager.getPhoneNumber(subId).takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        }
    }

    private companion object {
        const val LOCAL_TEST_ID = 9001
        const val TIMEOUT_MILLIS = 120_000L
    }
}
