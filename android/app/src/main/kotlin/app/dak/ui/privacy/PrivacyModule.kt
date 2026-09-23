package app.dak.ui.privacy

import android.content.Context
import android.util.AtomicFile
import app.dak.classify.CloudClassifier
import app.dak.classify.CloudVerdict
import app.dak.premium.consent.ConsentLedger
import app.dak.premium.consent.ConsentStorage
import app.dak.premium.consent.DataFlow
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Consent records live in no-backup storage (`allowBackup` is off anyway, and Dak's own backups never carry them):
 * a consent is given on this phone, to this disclosure text, and is never restored onto another phone.
 */
internal class FileConsentStorage(dir: File) : ConsentStorage {
    private val file = AtomicFile(File(dir, FILE_NAME))

    override fun read(): String? = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull()

    override fun write(json: String) {
        val out = file.startWrite()
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        const val FILE_NAME = "consent-records.json"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PrivacyModule {
    @Provides @Singleton
    fun consentLedger(@ApplicationContext context: Context): ConsentLedger =
        ConsentLedger(FileConsentStorage(context.noBackupFilesDir))
}

/**
 * Wrap any real [CloudClassifier] with this before binding it (premium flavour): Jev runs only while the user has
 * both turned the setting on **and** has a current consent for [DataFlow.CLOUD_CLASSIFICATION]. Either missing means
 * nothing is sent. (Settings import on restore can set the toggle, but never the consent.)
 */
class ConsentGatedCloudClassifier(
    private val delegate: CloudClassifier,
    private val consents: ConsentLedger,
    private val settings: SettingsStore,
) : CloudClassifier {
    override suspend fun classify(senderHeader: String?, maskedBody: String): CloudVerdict? {
        if (!settings.get(DakSettings.jevOptIn)) return null
        if (!consents.isGranted(DataFlow.CLOUD_CLASSIFICATION)) return null
        return delegate.classify(senderHeader, maskedBody)
    }
}
