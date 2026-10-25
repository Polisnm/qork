package com.smartlogic.triggerapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var excelManager: ExcelManager
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvStatsHeader: TextView
    private lateinit var tvLogOutput: TextView
    private lateinit var switchServiceEnabled: Switch
    private lateinit var switchAutoReply: Switch
    private lateinit var etWebhookUrl: EditText
    private lateinit var etTestCommand: EditText

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLogDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        excelManager = ExcelManager(this)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvStatsHeader = findViewById(R.id.tvStatsHeader)
        tvLogOutput = findViewById(R.id.tvLogOutput)
        switchServiceEnabled = findViewById(R.id.switchServiceEnabled)
        switchAutoReply = findViewById(R.id.switchAutoReply)
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etTestCommand = findViewById(R.id.etTestCommand)

        val prefs = getSharedPreferences("smart_logic_prefs", Context.MODE_PRIVATE)
        switchServiceEnabled.isChecked = prefs.getBoolean("service_enabled", true)
        switchAutoReply.isChecked = prefs.getBoolean("auto_reply", true)
        etWebhookUrl.setText(prefs.getString("webhook_url", ""))

        findViewById<Button>(R.id.btnGrantPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        switchServiceEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
        }

        switchAutoReply.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_reply", isChecked).apply()
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit().putString("webhook_url", etWebhookUrl.text.toString().trim()).apply()
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRunTest).setOnClickListener {
            val cmd = etTestCommand.text.toString().trim()
            if (cmd.isEmpty()) {
                Toast.makeText(this, "Enter a command like: !add Rahul, AC not cooling, Checked", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val response = excelManager.processCommand("Local Test", cmd)
            if (response != null) {
                Toast.makeText(this, response, Toast.LENGTH_LONG).show()
                etTestCommand.setText("")
                refreshLogDisplay()
            } else {
                Toast.makeText(this, "Use !add, !pending, !completed, !delete, or !summary", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnShareExcel).setOnClickListener {
            shareExcelFile()
        }

        findViewById<Button>(R.id.btnClearData).setOnClickListener {
            excelManager.clearAll()
            refreshLogDisplay()
            Toast.makeText(this, "Excel sheet cleared.", Toast.LENGTH_SHORT).show()
        }

        refreshLogDisplay()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        refreshLogDisplay()
        val filter = IntentFilter(WhatsAppTriggerService.ACTION_DATA_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun updatePermissionBanner() {
        val enabled = isNotificationServiceEnabled()
        if (enabled) {
            tvPermissionStatus.text = "✅ Notification Access: ENABLED (Listening for WhatsApp commands)"
            tvPermissionStatus.setBackgroundColor(Color.parseColor("#D1E7DD"))
            tvPermissionStatus.setTextColor(Color.parseColor("#0F5132"))
        } else {
            tvPermissionStatus.text = "⚠️ Notification Access: DISABLED (Tap the button below to enable)"
            tvPermissionStatus.setBackgroundColor(Color.parseColor("#FFF3CD"))
            tvPermissionStatus.setTextColor(Color.parseColor("#664D03"))
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, WhatsAppTriggerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun refreshLogDisplay() {
        val rows = excelManager.getAllRows()
        val pendingCount = rows.count { it.remark.contains("pending", ignoreCase = true) }
        val completedCount = rows.count { it.remark.contains("completed", ignoreCase = true) }
        tvStatsHeader.text = "Excel Rows (${rows.size} total | $completedCount Completed, $pendingCount Pending):"

        if (rows.isEmpty()) {
            tvLogOutput.text = "No entries recorded yet.\n\nTry sending on WhatsApp:\n" +
                "!add Rahul, AC not cooling, Visited site\n" +
                "!pending Neha, WiFi down, Part ordered\n" +
                "!completed Neha, Router replaced\n" +
                "!delete Rahul"
            return
        }

        val sb = StringBuilder()
        for (r in rows) {
            sb.append("#${r.id} | Date: ${r.date}\n")
            sb.append("   Name: ${r.name}\n")
            sb.append("   Complaint: ${r.complaint}\n")
            sb.append("   Remark: ${r.remark}\n")
            sb.append("----------------------------------------\n")
        }
        tvLogOutput.text = sb.toString()
    }

    private fun shareExcelFile() {
        val file = excelManager.exportToXlsxFile()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SmartLogic Complaints Excel Sheet")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export / Share Excel (.xlsx)"))
    }
}
