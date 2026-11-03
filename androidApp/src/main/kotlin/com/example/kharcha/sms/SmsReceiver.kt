package com.example.kharcha.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.kharcha.KharchaApplication
import com.example.kharcha.core.sms.SmsParser
import kotlinx.datetime.toKotlinLocalDate

/**
 * Receives incoming SMS, keeps only bank transaction messages and stores them.
 * Parsing happens entirely on-device; message text is never stored or uploaded,
 * only the extracted transaction.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val repository = (context.applicationContext as KharchaApplication).repository
        val parser = SmsParser()
        val today = java.time.LocalDate.now().toKotlinLocalDate()

        // Long SMS arrive as several parts; join them per sender before parsing.
        messages
            .groupBy { it.originatingAddress.orEmpty() }
            .forEach { (sender, parts) ->
                val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
                val parsed = parser.parse(sender, body) ?: return@forEach
                // A single indexed insert; fast enough to run inside the receiver.
                runCatching { repository.importSms(parsed, receivedOn = today) }
                    .onFailure { Log.w("Kharcha", "Could not import SMS from $sender", it) }
            }
    }
}
