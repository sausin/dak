package app.dak.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is the user on a call right now?" for the in-call OTP security warning. Uses the audio mode (covers VoIP
 * calls such as WhatsApp, needs no permission) and, when READ_PHONE_STATE is granted, the telephony call state.
 */
@Singleton
class CallStateDetector @Inject constructor(@ApplicationContext private val context: Context) {

    fun isInCall(): Boolean = audioInCall() || telephonyInCall()

    private fun audioInCall(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    @Suppress("DEPRECATION")
    private fun telephonyInCall(): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return try {
            telephony.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (e: SecurityException) {
            false
        }
    }
}
