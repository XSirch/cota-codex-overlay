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
import android.graphics.RectF
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
    private var trashRoot: FrameLayout? = null
    private var trashView: TrashTargetView? = null
    private var trashHot = false
    private var expanded = false
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("overlay_state", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            setRunning(false)
            stopSelf()
            return
        }
        setRunning(true)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dp(68),
            dp(68),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - dp(84))
            y = prefs.getInt("y", dp(180))
            dimAmount = 0f
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
        hideTrashTarget()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        setRunning(false)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setRunning(value: Boolean) {
        prefs.edit().putBoolean("running", value).apply()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, QuotaActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Cota Codex")
                .setContentText("Bolha ativa")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Cota Codex")
                .setContentText("Bolha ativa")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Bolha do Cota Codex",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun showCollapsed() {
        expanded = false
        hideTrashTarget()
        replaceRoot(dp(68), dp(68), buildBubble())
        updateBubbleFromCache()
    }

    private fun showExpanded() {
        expanded = true
        hideTrashTarget()
        replaceRoot(dp(328), WindowManager.LayoutParams.WRAP_CONTENT, buildPanel())
        refreshQuota()
    }

    private fun replaceRoot(width: Int, height: Int, child: View) {
        root?.let { runCatching { wm.removeView(it) } }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            clipChildren = false
            clipToPadding = false
        }
        frame.addView(child)
        root = frame
        params.width = width
        params.height = height
        clampToScreen(width)
        wm.addView(frame, params)
    }

    /**
     * The collapsed visual intentionally stays the same as v2. The important change is
     * that neither this view nor its transparent WindowManager root has elevation,
     * preventing Android from rasterising a square shadow layer behind the bubble.
     */
    private fun buildBubble(): View {
        val wrap = FrameLayout(this).apply {
            background = roundedGradient(
                intArrayOf(Color.rgb(25, 210, 165), Color.rgb(25, 126, 255)),
                24f
            )
            elevation = 0f
            setPadding(dp(3), dp(3), dp(3), dp(3))
            contentDescription = "Cota Codex. Toque para abrir ou arraste para mover."
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(10, 14, 17), 21f)
            elevation = 0f
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
        installDragTouch(wrap, allowClickToggle = true, offerTrash = true)
        return wrap
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = rounded(
                Color.rgb(18, 21, 23),
                16f,
                Color.rgb(47, 52, 54)
            )
            elevation = 0f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerText.addView(TextView(this).apply {
            text = "Codex"
            setTextColor(Color.rgb(238, 240, 239))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        headerText.addView(TextView(this).apply {
            id = 0x7420
            text = "Atualizando"
            setTextColor(Color.rgb(145, 153, 149))
            textSize = 10f
            setPadding(0, dp(1), 0, 0)
        })
        header.addView(headerText, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(actionText("↻", "Atualizar") { refreshQuota() })
        header.addView(actionText("—", "Recolher") { showCollapsed() })
        panel.addView(header)
        panel.addView(divider())

        val primary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }
        primary.addView(TextView(this).apply {
            text = "COTA PRINCIPAL"
            setTextColor(Color.rgb(132, 141, 137))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
        })

        val valueLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6), 0, 0)
        }
        valueLine.addView(TextView(this).apply {
            id = 0x7412
            text = "—%"
            setTextColor(Color.rgb(92, 200, 168))
            textSize = 40f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.BOTTOM
        }, LinearLayout.LayoutParams(0, -2, 1f))
        valueLine.addView(TextView(this).apply {
            id = 0x7413
            text = ""
            setTextColor(Color.rgb(155, 163, 159))
            textSize = 10f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(8), 0, 0, dp(7))
        })
        primary.addView(valueLine)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = 0x7421
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(92, 200, 168))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 53, 55))
        }
        primary.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10) })
        primary.addView(TextView(this).apply {
            id = 0x7422
            text = "Atualizando…"
            setTextColor(Color.rgb(235, 237, 236))
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        })
        primary.addView(TextView(this).apply {
            id = 0x7423
            text = ""
            setTextColor(Color.rgb(132, 141, 137))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, 0)
        })
        panel.addView(primary)

        panel.addView(divider())
        panel.addView(TextView(this).apply {
            text = "OUTRAS JANELAS"
            setTextColor(Color.rgb(132, 141, 137))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
            setPadding(0, dp(14), 0, dp(4))
        })
        panel.addView(LinearLayout(this).apply {
            id = 0x7414
            orientation = LinearLayout.VERTICAL
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        footer.addView(TextView(this).apply {
            id = 0x7415
            text = ""
            setTextColor(Color.rgb(121, 130, 126))
            textSize = 9f
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        footer.addView(TextView(this).apply {
            text = "Abrir app"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(108, 207, 177))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            minWidth = dp(72)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(2), 0)
            setOnClickListener {
                startActivity(
                    Intent(this@OverlayService, QuotaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        })
        panel.addView(footer)
        installDragTouch(panel, allowClickToggle = false, offerTrash = false)
        return panel
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.rgb(47, 52, 54))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun actionText(
        textValue: String,
        description: String,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = textValue
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(194, 199, 197))
        textSize = 20f
        typeface = Typeface.DEFAULT
        minWidth = dp(48)
        minHeight = dp(48)
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun installDragTouch(
        view: View,
        allowClickToggle: Boolean,
        offerTrash: Boolean
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    target.alpha = .88f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (abs(dx) > dp(5) || abs(dy) > dp(5))) {
                        moved = true
                        if (offerTrash) showTrashTarget()
                    }
                    if (moved) {
                        params.x = startX + dx
                        params.y = (startY + dy).coerceAtLeast(dp(20))
                        clampToScreen(params.width)
                        root?.let { wm.updateViewLayout(it, params) }
                        if (offerTrash) setTrashHot(isOverTrash(event.rawX, event.rawY))
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    target.alpha = 1f
                    if (!moved && allowClickToggle) {
                        showExpanded()
                    } else if (moved) {
                        val remove = offerTrash && isOverTrash(event.rawX, event.rawY)
                        hideTrashTarget()
                        if (remove) {
                            stopSelf()
                        } else {
                            snapEdge()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.alpha = 1f
                    hideTrashTarget()
                    if (moved) snapEdge()
                    true
                }

                else -> false
            }
        }
    }

    private fun showTrashTarget() {
        if (trashRoot != null) return
        trashHot = false
        val trash = TrashTargetView(this).apply { hot = false }
        trashView = trash
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(trash, FrameLayout.LayoutParams(-1, -1))
        }
        val trashParams = WindowManager.LayoutParams(
            dp(156),
            dp(72),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(30)
            dimAmount = 0f
        }
        trashRoot = frame
        wm.addView(frame, trashParams)
    }

    private fun hideTrashTarget() {
        trashRoot?.let { runCatching { wm.removeView(it) } }
        trashRoot = null
        trashView = null
        trashHot = false
    }

    private fun setTrashHot(hot: Boolean) {
        if (trashHot == hot) return
        trashHot = hot
        trashView?.hot = hot
        trashView?.invalidate()
    }

    private fun isOverTrash(rawX: Float, rawY: Float): Boolean {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val width = dp(176)
        val top = screenH - dp(122)
        val bottom = screenH - dp(12)
        val left = (screenW - width) / 2
        val right = left + width
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun snapEdge() {
        val screen = resources.displayMetrics.widthPixels
        val width = if (params.width > 0) params.width else dp(68)
        params.x = if (params.x + width / 2 < screen / 2) {
            dp(10)
        } else {
            screen - width - dp(10)
        }
        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
        root?.let { wm.updateViewLayout(it, params) }
    }

    private fun clampToScreen(width: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val safeW = if (width > 0) width else dp(68)
        params.x = params.x.coerceIn(
            dp(6),
            (screenW - safeW - dp(6)).coerceAtLeast(dp(6))
        )
        params.y = params.y.coerceIn(
            dp(30),
            (screenH - dp(120)).coerceAtLeast(dp(30))
        )
    }

    private fun updateBubbleFromCache() {
        val usage = AppCore.cachedUsage(this) ?: return
        val p = usage.windows.firstOrNull { it.group == "Codex" } ?: return
        root?.findViewById<TextView>(0x7410)?.text = "${p.remainingPercent.toInt()}%"
    }

    private fun refreshQuota() {
        if (!AppCore.SecureStore(this).hasToken()) {
            stopSelf()
            return
        }
        if (expanded) {
            root?.findViewById<TextView>(0x7422)?.text = "Atualizando…"
        }
        executor.execute {
            try {
                val usage = AppCore.fetchUsage(this)
                runOnUiThread { renderUsage(usage) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (expanded) {
                        root?.findViewById<TextView>(0x7422)?.text = "Falha ao atualizar"
                        root?.findViewById<TextView>(0x7423)?.text =
                            e.message ?: "Erro de conexão"
                    }
                }
            }
        }
    }

    private fun renderUsage(usage: AppCore.UsageData) {
        val primary = usage.windows.firstOrNull { it.group == "Codex" }
        if (!expanded) {
            root?.findViewById<TextView>(0x7410)?.text =
                primary?.let { "${it.remainingPercent.toInt()}%" } ?: "C"
            return
        }

        root?.findViewById<TextView>(0x7420)?.text = buildString {
            append(usage.plan.ifBlank { "ChatGPT" })
            if (usage.secureDnsUsed) append(" · DNS seguro")
        }

        val remaining = primary?.remainingPercent ?: 0.0
        val primaryColor = quotaColor(remaining)
        root?.findViewById<TextView>(0x7412)?.apply {
            text = primary?.let { "${it.remainingPercent.toInt()}%" } ?: "—%"
            setTextColor(primaryColor)
        }
        root?.findViewById<TextView>(0x7413)?.text =
            primary?.let { AppCore.windowLabel(it.windowSeconds) } ?: ""
        root?.findViewById<ProgressBar>(0x7421)?.apply {
            progress = (remaining * 10).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(primaryColor)
        }
        root?.findViewById<TextView>(0x7422)?.text =
            primary?.let { resetRelative(it.resetAt) } ?: "Sem dados"
        root?.findViewById<TextView>(0x7423)?.text =
            primary?.let { resetAbsolute(it.resetAt) } ?: ""

        val list = root?.findViewById<LinearLayout>(0x7414) ?: return
        list.removeAllViews()
        val others = usage.windows.drop(if (primary != null) 1 else 0).take(4)
        if (others.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nenhuma outra janela disponível"
                setTextColor(Color.rgb(132, 141, 137))
                textSize = 10f
                setPadding(0, dp(10), 0, dp(8))
            })
        } else {
            others.forEachIndexed { index, window ->
                list.addView(windowRow(window))
                if (index != others.lastIndex) list.addView(divider())
            }
        }
        root?.findViewById<TextView>(0x7415)?.text = "Atualizado agora"
    }

    private fun windowRow(w: AppCore.QuotaWindow): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        name.addView(TextView(this).apply {
            text = w.group
            setTextColor(Color.rgb(224, 227, 225))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        })
        name.addView(TextView(this).apply {
            text = AppCore.windowLabel(w.windowSeconds)
            setTextColor(Color.rgb(128, 137, 133))
            textSize = 9f
            setPadding(0, dp(2), 0, 0)
        })
        line.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this).apply {
            text = "${w.remainingPercent.toInt()}%"
            setTextColor(quotaColor(w.remainingPercent))
            textSize = 15f
            typeface = Typeface.MONOSPACE
        })
        row.addView(line)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (w.remainingPercent * 10).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(quotaColor(w.remainingPercent))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 53, 55))
        }
        row.addView(bar, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(7) })
        row.addView(TextView(this).apply {
            text = "${resetRelative(w.resetAt)} · ${resetAbsolute(w.resetAt)}"
            setTextColor(Color.rgb(128, 137, 133))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(5), 0, 0)
        })
        return row
    }

    private fun resetRelative(unixSeconds: Long): String {
        if (unixSeconds <= 0) return "Reset indisponível"
        val diff = unixSeconds * 1000L - System.currentTimeMillis()
        val mins = diff.coerceAtLeast(0L) / 60_000L
        val days = mins / 1440
        val hours = (mins % 1440) / 60
        val min = mins % 60
        return when {
            days > 0 -> "Renova em ${days}d ${hours}h"
            hours > 0 -> "Renova em ${hours}h ${min}min"
            else -> "Renova em ${min.coerceAtLeast(1)}min"
        }
    }

    private fun resetAbsolute(unixSeconds: Long): String {
        if (unixSeconds <= 0) return ""
        return DateTimeFormatter
            .ofPattern("dd/MM · HH:mm", Locale("pt", "BR"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(unixSeconds))
    }

    private fun quotaColor(v: Double): Int = when {
        v >= 50 -> Color.rgb(92, 200, 168)
        v >= 20 -> Color.rgb(210, 154, 58)
        else -> Color.rgb(225, 109, 115)
    }

    private fun rounded(
        color: Int,
        radius: Float,
        stroke: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun roundedGradient(colors: IntArray, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radius.toInt()).toFloat()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun runOnUiThread(block: () -> Unit) =
        android.os.Handler(mainLooper).post(block)

    private class TrashTargetView(context: Context) : View(context) {
        var hot: Boolean = false

        private val container = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val rect = RectF(2 * d, 2 * d, width - 2 * d, height - 2 * d)
            container.color = if (hot) Color.rgb(67, 28, 31) else Color.rgb(25, 28, 30)
            border.color = if (hot) Color.rgb(225, 109, 115) else Color.rgb(66, 72, 70)
            border.strokeWidth = 1 * d
            canvas.drawRoundRect(rect, 18 * d, 18 * d, container)
            canvas.drawRoundRect(rect, 18 * d, 18 * d, border)

            val cx = width / 2f
            val top = 14 * d
            icon.color = if (hot) Color.rgb(244, 139, 143) else Color.rgb(190, 197, 194)
            icon.strokeWidth = 1.8f * d

            val canLeft = cx - 7 * d
            val canRight = cx + 7 * d
            canvas.drawLine(canLeft, top + 5 * d, canRight, top + 5 * d, icon)
            canvas.drawLine(cx - 3 * d, top + 2 * d, cx + 3 * d, top + 2 * d, icon)
            canvas.drawRoundRect(
                RectF(canLeft + 1 * d, top + 8 * d, canRight - 1 * d, top + 20 * d),
                2 * d,
                2 * d,
                icon
            )
            canvas.drawLine(cx - 3 * d, top + 11 * d, cx - 3 * d, top + 17 * d, icon)
            canvas.drawLine(cx + 3 * d, top + 11 * d, cx + 3 * d, top + 17 * d, icon)

            label.color = if (hot) Color.rgb(244, 139, 143) else Color.rgb(190, 197, 194)
            label.textSize = 10 * d
            canvas.drawText(if (hot) "SOLTE PARA FECHAR" else "ARRASTE PARA FECHAR", cx, height - 12 * d, label)
        }
    }
}
