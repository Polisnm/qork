package com.smartlogic.triggerapp

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsAppTriggerService : NotificationListenerService() {

    private lateinit var excelManager: ExcelManager
    private var lastProcessedKey: String = ""
    private var lastProcessedTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        excelManager = ExcelManager(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        // Listen to WhatsApp and WhatsApp Business
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val prefs = getSharedPreferences("smart_logic_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("service_enabled", true)) return

        val extras = sbn.notification?.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: "WhatsApp User"
        val messageText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return

        if (!messageText.startsWith("!")) return

        // Avoid duplicate firing on notification summary updates
        val dedupSignature = "$sender|$messageText"
        val now = System.currentTimeMillis()
        if (dedupSignature == lastProcessedKey && (now - lastProcessedTime) < 2500L) {
            return
        }
        lastProcessedKey = dedupSignature
        lastProcessedTime = now

        val replyText = excelManager.processCommand(sender, messageText) ?: return

        // Broadcast update so MainActivity refreshes live
        sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))

        // Optionally send auto-reply back to WhatsApp notification
        if (prefs.getBoolean("auto_reply", true)) {
            sendWhatsAppReply(sbn.notification, replyText)
        }
    }

    private fun sendWhatsAppReply(notification: Notification?, replyMessage: String) {
        val actions = notification?.actions ?: return
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            if (remoteInputs.isNotEmpty() && action.actionIntent != null) {
                val intent = Intent()
                val bundle = Bundle()
                for (remoteInput in remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, replyMessage)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                try {
                    action.actionIntent.send(this, 0, intent)
                } catch (_: Exception) {
                }
                break
            }
        }
    }

    companion object {
        const val ACTION_DATA_UPDATED = "com.smartlogic.triggerapp.DATA_UPDATED"
    }
}
