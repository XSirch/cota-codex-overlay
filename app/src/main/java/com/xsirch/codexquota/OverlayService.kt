package com.xsirch.codexquota

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.xsirch.codexquota.REFRESH_OVERLAY"
        const val ACTION_STOP = "com.xsirch.codexquota.STOP_OVERLAY"
        private const val CHANNEL = "codex_quota_overlay"
        private const val NOTIFICATION_ID = 7401
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: FrameLayout? = null
    private var expanded = false
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("overlay_state", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(); return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dp(68), dp(68),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - dp(84))
            y = prefs.getInt("y", dp(180))
        }
        showCollapsed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> refreshQuota()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        root?.let { runCatching { wm.removeView(it) } }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, QuotaActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Cota Codex")
                .setContentText("Overlay ativo")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Cota Codex")
                .setContentText("Overlay ativo")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay do Cota Codex", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun showCollapsed() {
        expanded = false
        replaceRoot(dp(68), dp(68), buildBubble())
        updateBubbleFromCache()
    }

    private fun showExpanded() {
        expanded = true
        replaceRoot(dp(336), WindowManager.LayoutParams.WRAP_CONTENT, buildPanel())
        refreshQuota()
    }

    private fun replaceRoot(width: Int, height: Int, child: View) {
        root?.let { runCatching { wm.removeView(it) } }
        val frame = FrameLayout(this)
        frame.addView(child)
        root = frame
        params.width = width
        params.height = height
        clampToScreen(width)
        wm.addView(frame, params)
    }

    private fun buildBubble(): View {
        val wrap = FrameLayout(this).apply {
            background = roundedGradient(intArrayOf(Color.rgb(25, 210, 165), Color.rgb(25, 126, 255)), 24f)
            elevation = dp(10).toFloat()
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(10, 14, 17), 21f)
        }
        val percent = TextView(this).apply {
            id = 0x7410
            text = "C"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = "CODEX"
            setTextColor(Color.rgb(119, 242, 205))
            textSize = 7f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = .08f
        }
        inner.addView(percent, LinearLayout.LayoutParams(-1, 0, 1f))
        inner.addView(label, LinearLayout.LayoutParams(-1, dp(18)))
        wrap.addView(inner, FrameLayout.LayoutParams(-1, -1))
        installDragTouch(wrap)
        return wrap
    }

    private fun buildPanel(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.rgb(15, 19, 23), 22f, Color.rgb(40, 48, 54))
            elevation = dp(14).toFloat()
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "COTA CODEX"
            setTextColor(Color.rgb(236, 242, 240)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .05f
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        top.addView(actionText("↻") { refreshQuota() })
        top.addView(actionText("—") { showCollapsed() })
        card.addView(top)

        val hero = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(14)) }
        hero.addView(QuotaRingView(this).apply { id = 0x7411 }, LinearLayout.LayoutParams(dp(112), dp(112)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), 0, 0, 0) }
        info.addView(TextView(this).apply { id = 0x7412; text = "Atualizando…"; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD })
        info.addView(TextView(this).apply { id = 0x7413; text = ""; setTextColor(Color.rgb(157, 170, 178)); textSize = 12f; setPadding(0, dp(6), 0, 0) })
        hero.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(hero)

        card.addView(LinearLayout(this).apply { id = 0x7414; orientation = LinearLayout.VERTICAL })

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        footer.addView(TextView(this).apply { id = 0x7415; text = ""; setTextColor(Color.rgb(124, 137, 145)); textSize = 10f }, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(TextView(this).apply {
            text = "Abrir app"
            setTextColor(Color.rgb(87, 231, 190)); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                val i = Intent(this@OverlayService, QuotaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        })
        card.addView(footer)
        installDragTouch(card, allowClickToggle = false)
        return card
    }

    private fun actionText(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
        text = textValue
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(192, 202, 207))
        textSize = 22f
        setPadding(dp(9), 0, dp(9), 0)
        setOnClickListener { action() }
    }

    private fun installDragTouch(view: View, allowClickToggle: Boolean = true) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt(); val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    params.x = startX + dx; params.y = (startY + dy).coerceAtLeast(dp(24))
                    clampToScreen(params.width)
                    root?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && allowClickToggle) showExpanded() else snapEdge()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapEdge() {
        val screen = resources.displayMetrics.widthPixels
        val width = if (params.width > 0) params.width else dp(68)
        params.x = if (params.x + width / 2 < screen / 2) dp(10) else screen - width - dp(10)
        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
        root?.let { wm.updateViewLayout(it, params) }
    }

    private fun clampToScreen(width: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val safeW = if (width > 0) width else dp(68)
        params.x = params.x.coerceIn(dp(6), (screenW - safeW - dp(6)).coerceAtLeast(dp(6)))
        params.y = params.y.coerceIn(dp(30), (screenH - dp(120)).coerceAtLeast(dp(30)))
    }

    private fun updateBubbleFromCache() {
        val usage = AppCore.cachedUsage(this) ?: return
        val p = usage.windows.firstOrNull { it.group == "Codex" } ?: return
        root?.findViewById<TextView>(0x7410)?.text = "${p.remainingPercent.toInt()}%"
    }

    private fun refreshQuota() {
        if (!AppCore.SecureStore(this).hasToken()) {
            stopSelf(); return
        }
        if (expanded) {
            root?.findViewById<TextView>(0x7412)?.text = "Atualizando…"
        }
        executor.execute {
            try {
                val usage = AppCore.fetchUsage(this)
                runOnUiThread { renderUsage(usage) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (expanded) {
                        root?.findViewById<TextView>(0x7412)?.text = "Falha ao atualizar"
                        root?.findViewById<TextView>(0x7413)?.text = e.message ?: "Erro de conexão"
                    }
                }
            }
        }
    }

    private fun renderUsage(usage: AppCore.UsageData) {
        val primary = usage.windows.firstOrNull { it.group == "Codex" }
        if (!expanded) {
            root?.findViewById<TextView>(0x7410)?.text = primary?.let { "${it.remainingPercent.toInt()}%" } ?: "C"
            return
        }
        val ring = root?.findViewById<QuotaRingView>(0x7411)
        ring?.remaining = primary?.remainingPercent ?: 0.0
        ring?.invalidate()
        root?.findViewById<TextView>(0x7412)?.text = primary?.let { "${it.remainingPercent.toInt()}% restante" } ?: "Sem dados"
        root?.findViewById<TextView>(0x7413)?.text = primary?.let { "${AppCore.windowLabel(it.windowSeconds)} · ${resetText(it.resetAt)}" } ?: ""

        val list = root?.findViewById<LinearLayout>(0x7414) ?: return
        list.removeAllViews()
        usage.windows.drop(if (primary != null) 1 else 0).take(4).forEach { list.addView(windowRow(it)) }
        root?.findViewById<TextView>(0x7415)?.text = buildString {
            append(usage.plan.ifBlank { "Codex" })
            if (usage.secureDnsUsed) append(" · DNS seguro")
        }
    }

    private fun windowRow(w: AppCore.QuotaWindow): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(10))
            background = rounded(Color.rgb(22, 27, 32), 14f, Color.rgb(43, 51, 58))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(this).apply {
            text = "${w.group} · ${AppCore.windowLabel(w.windowSeconds)}"
            setTextColor(Color.rgb(221, 227, 225)); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this).apply {
            text = "${w.remainingPercent.toInt()}%"
            setTextColor(quotaColor(w.remainingPercent)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(line)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = (w.remainingPercent * 10).toInt(); progressTintList = android.content.res.ColorStateList.valueOf(quotaColor(w.remainingPercent)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(47, 55, 61))
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(8) })
        box.addView(TextView(this).apply { text = resetText(w.resetAt); setTextColor(Color.rgb(132, 145, 152)); textSize = 9f; setPadding(0, dp(6), 0, 0) })
        return box.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
    }

    private fun resetText(unixSeconds: Long): String {
        if (unixSeconds <= 0) return "Reset indisponível"
        val diff = unixSeconds * 1000L - System.currentTimeMillis()
        val mins = (diff.coerceAtLeast(0) / 60_000L)
        val days = mins / 1440; val hours = (mins % 1440) / 60; val min = mins % 60
        val relative = when { days > 0 -> "${days}d ${hours}h"; hours > 0 -> "${hours}h ${min}min"; else -> "${min.coerceAtLeast(1)}min" }
        val absolute = DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR")).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(unixSeconds))
        return "Renova em $relative · $absolute"
    }

    private fun quotaColor(v: Double): Int = when { v >= 50 -> Color.rgb(87, 231, 190); v >= 20 -> Color.rgb(255, 200, 87); else -> Color.rgb(255, 114, 124) }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun roundedGradient(colors: IntArray, radius: Float): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(radius.toInt()).toFloat() }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun runOnUiThread(block: () -> Unit) = android.os.Handler(mainLooper).post(block)

    class QuotaRingView(context: Context) : View(context) {
        var remaining = 0.0
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 49, 55); style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(87, 231, 190); style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 26f }
        private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(139, 151, 158); textAlign = Paint.Align.CENTER; textSize = 10f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            track.strokeWidth = 8 * d; fill.strokeWidth = 8 * d; text.textSize = 24 * d; sub.textSize = 9 * d
            val pad = 10 * d
            val rect = android.graphics.RectF(pad, pad, width - pad, height - pad)
            canvas.drawArc(rect, -90f, 360f, false, track)
            fill.color = when { remaining >= 50 -> Color.rgb(87,231,190); remaining >= 20 -> Color.rgb(255,200,87); else -> Color.rgb(255,114,124) }
            canvas.drawArc(rect, -90f, (360 * remaining / 100).toFloat(), false, fill)
            canvas.drawText("${remaining.toInt()}%", width/2f, height/2f + 3*d, text)
            canvas.drawText("restante", width/2f, height/2f + 20*d, sub)
        }
    }
}
