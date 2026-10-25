package com.smartlogic.triggerapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class SheetRow(
    val id: Long,
    val date: String,
    val name: String,
    val complaint: String,
    val remark: String
)

class ExcelManager(private val context: Context) :
    SQLiteOpenHelper(context, "smart_logic_complaints.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "date TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "complaint TEXT NOT NULL, " +
                "remark TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS entries")
        onCreate(db)
    }

    /**
     * Parses incoming WhatsApp text for trigger commands:
     * !add, !delete, !pending, !completed (plus !summary, !help).
     */
    fun processCommand(sender: String, rawMessage: String): String? {
        val text = rawMessage.trim()
        if (!text.startsWith("!")) return null

        val parts = text.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase(Locale.getDefault())
        val payload = if (parts.size > 1) parts[1].trim() else ""

        return when (cmd) {
            "!add" -> {
                if (payload.isEmpty()) {
                    "Usage: !add Name, Complaint, Remark (e.g. !add Rahul, AC not cooling, Checked)"
                } else {
                    val row = insertParsedEntry(sender, payload, defaultRemark = "Added")
                    exportToXlsxFile()
                    syncToWebhookAsync("add", row)
                    "✅ Added (#${row.id}):\nDate: ${row.date}\nName: ${row.name}\nComplaint: ${row.complaint}\nRemark: ${row.remark}"
                }
            }
            "!pending" -> {
                if (payload.isEmpty()) {
                    buildPendingSummary()
                } else {
                    // Check if user passed an existing ID or single Name to mark an existing row as Pending
                    val updatedExisting = tryUpdateExistingStatus(payload, targetStatus = "Pending")
                    if (updatedExisting != null) {
                        exportToXlsxFile()
                        syncToWebhookAsync("pending", updatedExisting)
                        "⏳ Marked as Pending (#${updatedExisting.id}):\nName: ${updatedExisting.name}\nComplaint: ${updatedExisting.complaint}\nRemark: ${updatedExisting.remark}"
                    } else {
                        val row = insertParsedEntry(sender, payload, defaultRemark = "Pending", statusPrefix = "Pending")
                        exportToXlsxFile()
                        syncToWebhookAsync("pending", row)
                        "⏳ Added as Pending (#${row.id}):\nDate: ${row.date}\nName: ${row.name}\nComplaint: ${row.complaint}\nRemark: ${row.remark}"
                    }
                }
            }
            "!completed", "!complete", "!done" -> {
                if (payload.isEmpty()) {
                    buildCompletedSummary()
                } else {
                    val updated = markEntryCompleted(sender, payload)
                    if (updated != null) {
                        exportToXlsxFile()
                        syncToWebhookAsync("completed", updated)
                        "✅ Marked Completed (#${updated.id}):\nDate: ${updated.date}\nName: ${updated.name}\nComplaint: ${updated.complaint}\nRemark: ${updated.remark}"
                    } else {
                        "⚠️ Could not find a matching entry for '$payload'."
                    }
                }
            }
            "!delete", "!del", "!remove" -> {
                if (payload.isEmpty()) {
                    "Usage: !delete <ID or Name> (e.g. !delete 2 or !delete Rahul)"
                } else if (payload.equals("all", ignoreCase = true)) {
                    clearAll()
                    "🗑️ Cleared all entries from the Excel sheet."
                } else {
                    val deleted = deleteEntry(payload)
                    if (deleted != null) {
                        exportToXlsxFile()
                        syncToWebhookAsync("delete", deleted)
                        "🗑️ Deleted (#${deleted.id}):\nName: ${deleted.name}\nComplaint: ${deleted.complaint}\nRemark: ${deleted.remark}"
                    } else {
                        "⚠️ Could not find an entry matching '$payload' to delete."
                    }
                }
            }
            "!summary", "!status", "!list" -> {
                buildFullSummary()
            }
            "!help" -> {
                "SmartLogic Trigger Commands:\n" +
                    "• !add Name, Complaint, Remark\n" +
                    "• !pending Name, Complaint, Remark\n" +
                    "• !pending (list pending rows)\n" +
                    "• !completed ID or Name, Remark\n" +
                    "• !completed (list completed rows)\n" +
                    "• !delete ID or Name\n" +
                    "• !summary"
            }
            else -> null
        }
    }

    private fun insertParsedEntry(
        sender: String,
        payload: String,
        defaultRemark: String,
        statusPrefix: String? = null
    ): SheetRow {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        var name = sender
        var complaint = payload
        var remark = defaultRemark

        if (payload.contains(",")) {
            val tokens = payload.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            when {
                tokens.size >= 3 -> {
                    name = tokens[0]
                    complaint = tokens[1]
                    val customRemark = tokens.drop(2).joinToString(", ")
                    remark = if (statusPrefix != null && !customRemark.contains(statusPrefix, ignoreCase = true)) {
                        "$statusPrefix - $customRemark"
                    } else {
                        customRemark
                    }
                }
                tokens.size == 2 -> {
                    name = tokens[0]
                    complaint = tokens[1]
                    remark = defaultRemark
                }
                tokens.size == 1 -> {
                    name = sender
                    complaint = tokens[0]
                    remark = defaultRemark
                }
            }
        }

        val values = ContentValues().apply {
            put("date", now)
            put("name", name)
            put("complaint", complaint)
            put("remark", remark)
        }
        val id = writableDatabase.insert("entries", null, values)
        return SheetRow(id, now, name, complaint, remark)
    }

    /**
     * Used when user sends "!pending 2" or "!pending Rahul" (single parameter or ID) to mark an existing row as Pending.
     */
    private fun tryUpdateExistingStatus(payload: String, targetStatus: String): SheetRow? {
        val tokens = payload.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val firstToken = tokens.firstOrNull() ?: return null
        val allRows = getAllRows()

        // If first token is an ID number (e.g. "!pending 2" or "!pending 2, Waiting for part")
        val byId = firstToken.toLongOrNull()?.let { id -> allRows.find { it.id == id } }
        if (byId != null) {
            val extraNote = if (tokens.size > 1) tokens.drop(1).joinToString(", ") else ""
            val updatedRemark = if (extraNote.isNotEmpty()) "$targetStatus - $extraNote" else targetStatus
            val values = ContentValues().apply { put("remark", updatedRemark) }
            writableDatabase.update("entries", values, "id = ?", arrayOf(byId.id.toString()))
            return byId.copy(remark = updatedRemark)
        }

        // If single token without commas matches an existing Name (e.g. "!pending Rahul")
        if (tokens.size == 1) {
            val byName = allRows.find {
                it.name.equals(firstToken, ignoreCase = true) ||
                    it.complaint.equals(firstToken, ignoreCase = true)
            }
            if (byName != null) {
                val values = ContentValues().apply { put("remark", targetStatus) }
                writableDatabase.update("entries", values, "id = ?", arrayOf(byName.id.toString()))
                return byName.copy(remark = targetStatus)
            }
        }
        return null
    }

    /**
     * Handles "!completed <ID or Name>[, <Optional Remark>]" or logs a new Completed row if 3 fields are given and no existing row matches.
     */
    private fun markEntryCompleted(sender: String, payload: String): SheetRow? {
        val tokens = payload.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val target = tokens[0]
        val allRows = getAllRows()

        // Prefer matching by ID, then matching a Pending entry by Name/Complaint, then any entry by Name/Complaint
        val match = target.toLongOrNull()?.let { id -> allRows.find { it.id == id } }
            ?: allRows.find {
                it.remark.contains("pending", ignoreCase = true) &&
                    (it.name.contains(target, ignoreCase = true) || it.complaint.contains(target, ignoreCase = true))
            }
            ?: allRows.find {
                it.name.contains(target, ignoreCase = true) || it.complaint.contains(target, ignoreCase = true)
            }

        if (match != null) {
            val extraNote = if (tokens.size > 1) tokens.drop(1).joinToString(", ") else ""
            val newRemark = when {
                extraNote.isEmpty() -> "Completed"
                extraNote.contains("completed", ignoreCase = true) -> extraNote
                else -> "Completed - $extraNote"
            }
            val values = ContentValues().apply { put("remark", newRemark) }
            writableDatabase.update("entries", values, "id = ?", arrayOf(match.id.toString()))
            return match.copy(remark = newRemark)
        }

        // If no existing entry matched and user provided "!completed Name, Complaint[, Remark]", insert as a new Completed row
        if (tokens.size >= 2) {
            return insertParsedEntry(sender, payload, defaultRemark = "Completed", statusPrefix = "Completed")
        }
        return null
    }

    /**
     * Deletes a row by ID, Name, or Complaint text and returns the deleted row.
     */
    private fun deleteEntry(target: String): SheetRow? {
        val cleanTarget = target.trim().removePrefix("#")
        val allRows = getAllRows()
        val match = cleanTarget.toLongOrNull()?.let { id -> allRows.find { it.id == id } }
            ?: allRows.find {
                it.name.equals(cleanTarget, ignoreCase = true) ||
                    it.complaint.equals(cleanTarget, ignoreCase = true)
            }
            ?: allRows.find {
                it.name.contains(cleanTarget, ignoreCase = true) ||
                    it.complaint.contains(cleanTarget, ignoreCase = true)
            }

        if (match != null) {
            writableDatabase.delete("entries", "id = ?", arrayOf(match.id.toString()))
            return match
        }
        return null
    }

    fun getAllRows(): List<SheetRow> {
        val list = mutableListOf<SheetRow>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, date, name, complaint, remark FROM entries ORDER BY id DESC",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    SheetRow(
                        id = c.getLong(0),
                        date = c.getString(1),
                        name = c.getString(2),
                        complaint = c.getString(3),
                        remark = c.getString(4)
                    )
                )
            }
        }
        return list
    }

    fun clearAll() {
        writableDatabase.delete("entries", null, null)
        exportToXlsxFile()
    }

    private fun buildPendingSummary(): String {
        val pending = getAllRows().filter { it.remark.contains("pending", ignoreCase = true) }
        if (pending.isEmpty()) {
            return "🎉 No pending complaints in the Excel sheet!"
        }
        val lines = pending.take(15).joinToString("\n") {
            "#${it.id} [${it.date}] ${it.name}: ${it.complaint} (${it.remark})"
        }
        return "⏳ Pending Complaints (${pending.size}):\n$lines"
    }

    private fun buildCompletedSummary(): String {
        val completed = getAllRows().filter { it.remark.contains("completed", ignoreCase = true) }
        if (completed.isEmpty()) {
            return "ℹ️ No completed complaints found yet. Use !completed <ID or Name> to mark a row as Completed."
        }
        val lines = completed.take(15).joinToString("\n") {
            "#${it.id} [${it.date}] ${it.name}: ${it.complaint} (${it.remark})"
        }
        return "✅ Completed Complaints (${completed.size}):\n$lines"
    }

    private fun buildFullSummary(): String {
        val rows = getAllRows()
        val pendingCount = rows.count { it.remark.contains("pending", ignoreCase = true) }
        val completedCount = rows.count { it.remark.contains("completed", ignoreCase = true) }
        val addedCount = rows.size - pendingCount - completedCount
        return "📊 Excel Sheet Summary:\n" +
            "• Total Rows: ${rows.size}\n" +
            "• Completed: $completedCount\n" +
            "• Pending: $pendingCount\n" +
            "• Other / Added: $addedCount"
    }

    /**
     * Generates a genuine Microsoft Excel (.xlsx) OpenXML workbook with columns:
     * Date | Name | Complaint | Remark
     */
    fun exportToXlsxFile(): File {
        val rows = getAllRows().reversed() // chronological order in Excel
        val outFile = File(context.filesDir, "SmartLogicTriggers.xlsx")
        writeXlsxWorkbook(outFile, rows)

        // Also save a copy into public Documents folder when accessible
        try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (docsDir != null && (docsDir.exists() || docsDir.mkdirs())) {
                val publicFile = File(docsDir, "SmartLogicTriggers.xlsx")
                outFile.copyTo(publicFile, overwrite = true)
            }
        } catch (_: Exception) {
        }
        return outFile
    }

    private fun writeXlsxWorkbook(file: File, rows: List<SheetRow>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // 1. [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            val contentTypesXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                "  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
                "  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n" +
                "  <Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n" +
                "</Types>"
            zip.write(contentTypesXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2. _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            val relsXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
                "</Relationships>"
            zip.write(relsXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 3. xl/workbook.xml
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            val workbookXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
                "  <sheets>\n" +
                "    <sheet name=\"Complaints\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
                "  </sheets>\n" +
                "</workbook>"
            zip.write(workbookXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 4. xl/_rels/workbook.xml.rels
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            val wbRelsXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n" +
                "  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n" +
                "</Relationships>"
            zip.write(wbRelsXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 5. xl/styles.xml
            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            val stylesXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
                "  <fonts count=\"2\">\n" +
                "    <font><sz val=\"11\"/><name val=\"Calibri\"/></font>\n" +
                "    <font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>\n" +
                "  </fonts>\n" +
                "  <fills count=\"2\">\n" +
                "    <fill><patternFill patternType=\"none\"/></fill>\n" +
                "    <fill><patternFill patternType=\"gray125\"/></fill>\n" +
                "  </fills>\n" +
                "  <borders count=\"1\">\n" +
                "    <border><left/><right/><top/><bottom/><diagonal/></border>\n" +
                "  </borders>\n" +
                "  <cellStyleXfs count=\"1\">\n" +
                "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>\n" +
                "  </cellStyleXfs>\n" +
                "  <cellXfs count=\"2\">\n" +
                "    <xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>\n" +
                "    <xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>\n" +
                "  </cellXfs>\n" +
                "</styleSheet>"
            zip.write(stylesXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 6. xl/worksheets/sheet1.xml (Columns: Date, Name, Complaint, Remark)
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")
            sb.append("  <sheetData>\n")

            // Header Row (row 1): Date | Name | Complaint | Remark
            val headers = listOf("Date", "Name", "Complaint", "Remark")
            sb.append("    <row r=\"1\">\n")
            headers.forEachIndexed { idx, h ->
                val colLetter = ('A' + idx)
                sb.append("      <c r=\"${colLetter}1\" s=\"1\" t=\"inlineStr\"><is><t>${escapeXml(h)}</t></is></c>\n")
            }
            sb.append("    </row>\n")

            // Data Rows
            rows.forEachIndexed { index, r ->
                val rowNum = index + 2
                sb.append("    <row r=\"$rowNum\">\n")
                sb.append("      <c r=\"A$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(r.date)}</t></is></c>\n")
                sb.append("      <c r=\"B$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(r.name)}</t></is></c>\n")
                sb.append("      <c r=\"C$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(r.complaint)}</t></is></c>\n")
                sb.append("      <c r=\"D$rowNum\" t=\"inlineStr\"><is><t>${escapeXml(r.remark)}</t></is></c>\n")
                sb.append("    </row>\n")
            }

            sb.append("  </sheetData>\n")
            sb.append("</worksheet>")
            zip.write(sb.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun syncToWebhookAsync(action: String, row: SheetRow) {
        val prefs = context.getSharedPreferences("smart_logic_prefs", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "")?.trim() ?: ""
        if (webhookUrl.isEmpty() || !webhookUrl.startsWith("http")) return

        Thread {
            try {
                val url = URL(webhookUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val payload = JSONObject().apply {
                    put("action", action)
                    put("id", row.id)
                    put("date", row.date)
                    put("name", row.name)
                    put("complaint", row.complaint)
                    put("remark", row.remark)
                }
                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }
}
