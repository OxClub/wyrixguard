package com.wyrix.guard.report

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.wyrix.guard.core.Accuracy
import com.wyrix.guard.core.Csv
import com.wyrix.guard.data.local.EventEntity
import com.wyrix.guard.data.local.LocationEntity
import java.io.File
import java.text.DateFormat
import java.util.Date

data class ReportData(
    val deviceName: String, val ownerImei: String?, val ownerNote: String, val fingerprint: String,
    val lastFix: LocationEntity?, val lastSyncAt: Long, val events: List<EventEntity>,
)

object ReportGenerator {
    private fun fmt(ms: Long) = if (ms <= 0) "never" else DateFormat.getDateTimeInstance().format(Date(ms))

    fun lines(d: ReportData): List<String> = buildList {
        add("WYRIX GUARD - Device Recovery Report"); add("Generated: ${fmt(System.currentTimeMillis())}")
        add("Owner-generated summary. NOT an official police or telecom document."); add("")
        add("A. OWNER CLAIMS (typed by the account holder, not verified)")
        add("Device name: ${d.deviceName}"); add("IMEI (owner-entered): ${d.ownerImei ?: "not provided"}")
        if (d.ownerNote.isNotBlank()) add("Note: ${d.ownerNote}")
        add(""); add("B. VERIFIED APP RECORDS (reported by this enrolled device)")
        add("Device key fingerprint: ${d.fingerprint}"); add("Last sync: ${fmt(d.lastSyncAt)}")
        val f = d.lastFix
        if (f == null) add("Last confirmed location: none recorded") else {
            add("Last confirmed location: %.6f, %.6f".format(f.lat, f.lng))
            add("Accuracy: ${Accuracy.label(f.accuracy)}; recorded ${fmt(f.recordedAt)}")
        }
        add(""); add("Recent security events:")
        if (d.events.isEmpty()) add("  none") else d.events.take(15).forEach { add("  ${fmt(it.occurredAt)} - ${it.type}") }
        add(""); add("Limits: location may be out of date or approximate; a phone cannot be located by IMEI alone.")
        add("To block a stolen phone contact your operator and national registry (e.g. CEIR / Sanchar Saathi, India).")
    }

    private fun wrap(s: String, max: Int = 90): List<String> =
        if (s.length <= max) listOf(s) else s.chunked(max)

    fun pdf(ctx: Context, d: ReportData): File {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 11f }
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        var y = 48f; var n = 1
        for (line in lines(d).flatMap { wrap(it) }) {
            if (y > 800f) { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 48f }
            page.canvas.drawText(line, 40f, y, paint); y += 16f
        }
        doc.finishPage(page)
        val f = File(ctx.cacheDir, "reports").apply { mkdirs() }.let { File(it, "wyrix-recovery-report.pdf") }
        f.outputStream().use { doc.writeTo(it) }; doc.close()
        return f
    }

    fun csv(ctx: Context, locations: List<LocationEntity>): File {
        val text = Csv.build(listOf("recorded_at_ms", "latitude", "longitude", "accuracy_m", "battery_pct", "connection", "mode"),
            locations.map { listOf(it.recordedAt, it.lat, it.lng, it.accuracy, it.battery, it.connection, it.mode) })
        return File(ctx.cacheDir, "reports").apply { mkdirs() }.let { File(it, "wyrix-location-history.csv") }.also { it.writeText(text) }
    }

    fun shareIntent(ctx: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share report")
    }
}
