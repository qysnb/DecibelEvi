package com.decibelevidence.app.capture

import android.graphics.*
import com.decibelevidence.app.model.OverlayState
import com.decibelevidence.core.levelText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The bitmap is burned into the shared framebuffer, before either preview or encoder output. */
internal class OverlayPainter(private val width: Int, private val height: Int) {
    private val landscape = width > height
    private val scale = minOf(width,height) / 720f
    val panelHeight = (230f * scale).toInt()
    val bitmap: Bitmap = Bitmap.createBitmap(width, panelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var paintedNs = Long.MIN_VALUE
    private var paintedKey = ""

    fun update(state: OverlayState, atNs: Long): Boolean {
        val key = "${state.sessionId}:${state.sessionStartNs}:${state.recordingStartNs}:${state.levelReference}:${state.measurement?.levelReference}:${state.isPhoto}:${state.graphStartNs}"
        if (paintedNs != Long.MIN_VALUE && atNs-paintedNs in 0 until 250_000_000L && key == paintedKey) return false
        paintedNs = atNs; paintedKey = key
        bitmap.eraseColor(Color.TRANSPARENT)
        val top = 0f
        paint.color = Color.argb(220, 5, 13, 20)
        canvas.drawRect(0f, top, width.toFloat(), panelHeight.toFloat(), paint)
        val x = 22f * scale
        var y = top + 32f * scale
        val snapshot = state.measurement
        val fresh = snapshot != null && atNs - snapshot.timeNs in 0..2_000_000_000L && snapshot.valid
        val reference = state.levelReference ?: snapshot?.levelReference
        val unit = reference?.unitLabel ?: "估算 dB(A)"
        val preview = state.sessionId.isBlank()
        fun value(v: Double?) = if (reference != null) levelText(v, reference) else "—"
        val header = "当前 ${if (fresh) value(snapshot?.currentDb) else "—"} $unit    AVG ${value(snapshot?.averageDb)}    MAX ${value(snapshot?.maximumDb)}"
        fittedText(header, x, y, 25f)
        val graphTop = 55f * scale
        val graphBottom = 125f * scale
        val graphAxis = x
        val graphLeft = graphAxis + 46f * scale
        val graphRight = width - x
        val curve = snapshot?.curve.orEmpty()
        val end = maxOf(curve.lastOrNull()?.elapsedSeconds ?: 0L, ((atNs - state.graphStartNs).coerceAtLeast(0) / 1_000_000_000L - 1).coerceAtLeast(0))
        val points = curve.filter { it.elapsedSeconds >= end - 59 }
        val values = points.flatMap { listOfNotNull(it.db, it.maximumDb) }.filter { it.isFinite() }
        val lower = if (reference?.isAcoustic != false) minOf(30.0, (values.minOrNull() ?: 30.0) - 5) else minOf(-80.0, (values.minOrNull() ?: -80.0) - 5)
        val upper = maxOf(lower + 30, (values.maxOrNull() ?: (lower + 60)) + 5)
        paint.strokeWidth = scale
        for (i in 0..2) {
            val gy = graphTop + i * (graphBottom - graphTop) / 2
            paint.color = Color.rgb(71, 86, 96)
            canvas.drawLine(graphLeft, gy, graphRight, gy, paint)
            text("${(upper - i * (upper - lower) / 2).toInt()}", graphAxis, gy + 6f * scale, 15f, Color.LTGRAY)
        }
        fun line(maximum: Boolean, color: Int) {
            val path = Path()
            var connected = false
            var previous = Long.MIN_VALUE
            for (p in points) {
                val v = if (maximum) p.maximumDb else p.db
                if (v == null || !v.isFinite()) { connected = false; continue }
                val px = graphLeft + ((p.elapsedSeconds - (end - 59)) / 59f) * (graphRight - graphLeft)
                val py = graphBottom - ((v - lower) / (upper - lower)).toFloat().coerceIn(0f, 1f) * (graphBottom - graphTop)
                if (!connected || p.elapsedSeconds != previous + 1) path.moveTo(px, py) else path.lineTo(px, py)
                connected = true; previous = p.elapsedSeconds
            }
            paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * scale
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        }
        line(true, Color.rgb(255, 192, 100)); line(false, Color.rgb(97, 231, 206))
        text("最近60秒 青:均值 橙:Fast最大", graphAxis, graphBottom + 23f * scale, 16f, Color.LTGRAY)
        y = 181f * scale
        date.timeZone = java.util.TimeZone.getDefault()
        val elapsed = if (state.isPhoto) 0L else atNs - (state.recordingStartNs ?: state.sessionStartNs)
        fittedText("${date.format(Date(state.wallTimeMs))}    ${if (preview) "预览" else "取证"} ${duration(elapsed)}", x, y, 22f)
        y += 29f * scale
        val location = state.location
        val accuracy = location?.accuracyMeters?.let { String.format(Locale.US,"±%.0fm",it) } ?: "精度未知"
        val position = if (location?.hasCoordinates == true) String.format(Locale.US, "%.6f, %.6f  %s", location.latitude, location.longitude,accuracy) else "坐标不可用"
        val status = (location?.status(atNs) ?: "未定位") + if (location?.approximate == true && location.status(atNs) != "大致位置") "·大致" else ""
        text("$status  $position", x, y, 18f)
        return true
    }

    private fun fittedText(value: String, x: Float, y: Float, size: Float) {
        paint.textSize = size * scale
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val available = width - x - 22f * scale
        val fittedSize = size * minOf(1f, available / paint.measureText(value).coerceAtLeast(1f))
        text(value, x, y, fittedSize)
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
        paint.color = color; paint.textSize = size * scale; paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.style = Paint.Style.FILL
        var fit = value
        val available = width.toFloat() - x - 16f * scale
        if (paint.measureText(fit) > available) {
            val count = paint.breakText(fit, true, available - paint.measureText("…"), null)
            fit = fit.take(count) + "…"
        }
        canvas.drawText(fit, x, y, paint)
    }

    fun close() = bitmap.recycle()
    private fun duration(ns: Long): String {
        val seconds = ns.coerceAtLeast(0) / 1_000_000_000L
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}
