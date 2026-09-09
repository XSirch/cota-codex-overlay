package com.xsirch.codexquota

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class QuotaActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val state = mutableStateOf<ScreenState>(ScreenState.Loading)
    private val overlayRunning = mutableStateOf(false)
    private var pendingOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QuotaTheme { App() } }
        refreshOverlayState()
        refreshInitial()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayState()
        if (pendingOverlayPermission && Settings.canDrawOverlays(this)) {
            pendingOverlayPermission = false
            startOverlay()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshOverlayState() {
        overlayRunning.value = getSharedPreferences("overlay_state", MODE_PRIVATE)
            .getBoolean("running", false)
    }

    private fun refreshInitial() {
        if (!AppCore.SecureStore(this).hasToken()) {
            state.value = ScreenState.SignedOut
            return
        }
        loadUsage()
    }

    private fun directLogin() {
        state.value = ScreenState.LoggingIn
        executor.execute {
            try {
                val session = AppCore.beginBrowserLogin()
                runOnUiThread {
                    state.value = ScreenState.WaitingBrowser
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.authUrl)))
                }
                AppCore.finishBrowserLogin(this, session)
                val usage = AppCore.fetchUsage(this)
                runOnUiThread {
                    state.value = ScreenState.Ready(usage)
                    if (Settings.canDrawOverlays(this)) startOverlay(showToast = false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    state.value = ScreenState.Error(e.message ?: "Falha ao entrar no ChatGPT.")
                }
            }
        }
    }

    private fun loadUsage() {
        state.value = ScreenState.Loading
        executor.execute {
            try {
                val usage = AppCore.fetchUsage(this)
                runOnUiThread { state.value = ScreenState.Ready(usage) }
            } catch (e: Exception) {
                runOnUiThread {
                    state.value = ScreenState.Error(e.message ?: "Não foi possível atualizar a cota.")
                }
            }
        }
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            startOverlay()
            return
        }
        pendingOverlayPermission = true
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun startOverlay(showToast: Boolean = true) {
        if (!Settings.canDrawOverlays(this)) return
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 830)
            }
        }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        overlayRunning.value = true
        if (showToast) Toast.makeText(this, "Bolha ativada", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlay(showToast: Boolean = true) {
        stopService(Intent(this, OverlayService::class.java))
        overlayRunning.value = false
        if (showToast) Toast.makeText(this, "Bolha desativada", Toast.LENGTH_SHORT).show()
    }

    private fun logout() {
        stopOverlay(showToast = false)
        AppCore.SecureStore(this).clear()
        state.value = ScreenState.SignedOut
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val s = state.value
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Text(
                            "Cota Codex",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                    },
                    actions = {
                        if (s is ScreenState.Ready) {
                            IconButton(onClick = { loadUsage() }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar cota")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (s) {
                ScreenState.SignedOut -> LoginScreen(padding)
                ScreenState.LoggingIn -> LoadingScreen(
                    padding,
                    "Preparando login",
                    "Abrindo a autenticação segura do ChatGPT."
                )
                ScreenState.WaitingBrowser -> WaitingBrowserScreen(padding)
                ScreenState.Loading -> LoadingScreen(
                    padding,
                    "Atualizando cota",
                    "Consultando os limites atuais do Codex."
                )
                is ScreenState.Ready -> Dashboard(padding, s.usage)
                is ScreenState.Error -> ErrorScreen(padding, s.message)
            }
        }
    }

    @Composable
    private fun LoginScreen(padding: PaddingValues) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)
        ) {
            item {
                Text(
                    "Consulte sua cota sem interromper o que estiver fazendo.",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 34.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "A bolha fica disponível sobre outros apps e mostra quanto resta e quando cada janela renova.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(32.dp))
                CapabilityRow(
                    Icons.Rounded.TouchApp,
                    "Bolha flutuante",
                    "Toque para abrir, arraste para mover e solte na lixeira para encerrar."
                )
                HorizontalDivider(Modifier.padding(start = 44.dp, top = 16.dp, bottom = 16.dp))
                CapabilityRow(
                    Icons.Rounded.Login,
                    "Login direto",
                    "A autenticação acontece no navegador e retorna automaticamente ao app."
                )
                HorizontalDivider(Modifier.padding(start = 44.dp, top = 16.dp, bottom = 16.dp))
                CapabilityRow(
                    Icons.Rounded.Security,
                    "Credenciais protegidas",
                    "Sua senha não passa pelo aplicativo; os tokens ficam no Android Keystore."
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { directLogin() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.Login, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Entrar com ChatGPT", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    @Composable
    private fun CapabilityRow(icon: ImageVector, title: String, body: String) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(20.dp))
            Column {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }

    @Composable
    private fun WaitingBrowserScreen(padding: PaddingValues) {
        Box(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Conclua o login no navegador",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Quando a autorização terminar, esta tela continua automaticamente.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp
                )
            }
        }
    }

    @Composable
    private fun Dashboard(padding: PaddingValues, usage: AppCore.UsageData) {
        val primary = usage.windows.firstOrNull { it.group == "Codex" }
        val otherWindows = usage.windows.drop(if (primary != null) 1 else 0)
        val permissionGranted = Settings.canDrawOverlays(this)
        val running = overlayRunning.value

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item {
                Text(
                    usage.plan.ifBlank { "Codex" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Uso",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp
                )
                Spacer(Modifier.height(16.dp))
            }

            if (primary != null) {
                item {
                    PrimaryQuota(primary)
                    Spacer(Modifier.height(28.dp))
                }
            }

            if (otherWindows.isNotEmpty()) {
                item {
                    SectionLabel("OUTRAS JANELAS")
                    Spacer(Modifier.height(6.dp))
                }
                itemsIndexed(otherWindows) { index, window ->
                    WindowRow(window)
                    if (index != otherWindows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }

            item {
                SectionLabel("OVERLAY")
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Bolha flutuante", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                running -> "Ativa sobre outros aplicativos"
                                permissionGranted -> "Permissão concedida · bolha desativada"
                                else -> "Requer permissão para aparecer sobre outros apps"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (permissionGranted) startOverlay() else requestOverlay()
                            } else {
                                stopOverlay()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Mover ou fechar", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Arraste a bolha pela tela. Durante o arrasto, solte-a na lixeira para encerrar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            item {
                SectionLabel("CONTA")
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Plano", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            usage.plan.ifBlank { "ChatGPT" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    if (usage.secureDnsUsed) {
                        Text(
                            "DNS seguro",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { logout() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Text("Sair da conta", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun PrimaryQuota(w: AppCore.QuotaWindow) {
        val color = quotaColor(w.remainingPercent)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .64f)
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                SectionLabel("COTA PRINCIPAL")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${w.remainingPercent.toInt()}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 46.sp,
                        lineHeight = 48.sp
                    )
                    Text(
                        "%",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        AppCore.windowLabel(w.windowSeconds),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = (w.remainingPercent / 100).toFloat(),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    resetRelative(w.resetAt),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    resetAbsolute(w.resetAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }

    @Composable
    private fun WindowRow(w: AppCore.QuotaWindow) {
        val color = quotaColor(w.remainingPercent)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.group, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        AppCore.windowLabel(w.windowSeconds),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Text(
                    "${w.remainingPercent.toInt()}%",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = (w.remainingPercent / 100).toFloat(),
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = color,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${resetRelative(w.resetAt)} · ${resetAbsolute(w.resetAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.1.sp
        )
    }

    @Composable
    private fun LoadingScreen(padding: PaddingValues, title: String, body: String) {
        Box(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.height(20.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(5.dp))
                Text(
                    body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }
        }
    }

    @Composable
    private fun ErrorScreen(padding: PaddingValues, message: String) {
        Box(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.height(18.dp))
                Text("Não foi possível continuar", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { refreshInitial() },
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Tentar novamente") }
                TextButton(
                    onClick = {
                        AppCore.SecureStore(this@QuotaActivity).clear()
                        state.value = ScreenState.SignedOut
                    }
                ) { Text("Voltar ao login") }
            }
        }
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
            .ofPattern("dd/MM/yyyy · HH:mm", Locale("pt", "BR"))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(unixSeconds))
    }

    @Composable
    private fun quotaColor(v: Double): Color = when {
        v >= 50 -> MaterialTheme.colorScheme.primary
        v >= 20 -> Color(0xFFB77700)
        else -> MaterialTheme.colorScheme.error
    }

    private sealed interface ScreenState {
        data object SignedOut : ScreenState
        data object LoggingIn : ScreenState
        data object WaitingBrowser : ScreenState
        data object Loading : ScreenState
        data class Ready(val usage: AppCore.UsageData) : ScreenState
        data class Error(val message: String) : ScreenState
    }
}

private val QuotaDarkColors = darkColorScheme(
    primary = Color(0xFF5CC8A8),
    onPrimary = Color(0xFF062E24),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFE9ECEA),
    surface = Color(0xFF14171A),
    onSurface = Color(0xFFE9ECEA),
    surfaceVariant = Color(0xFF1A1E21),
    onSurfaceVariant = Color(0xFF9EA7A3),
    outline = Color(0xFF444B48),
    outlineVariant = Color(0xFF292E2C),
    error = Color(0xFFE16D73)
)

private val QuotaLightColors = lightColorScheme(
    primary = Color(0xFF166A55),
    onPrimary = Color.White,
    background = Color(0xFFF7F8F6),
    onBackground = Color(0xFF181B1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181B1A),
    surfaceVariant = Color(0xFFF0F2EF),
    onSurfaceVariant = Color(0xFF626A66),
    outline = Color(0xFF777E7A),
    outlineVariant = Color(0xFFDDE1DE),
    error = Color(0xFFB3261E)
)

@Composable
private fun QuotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) QuotaDarkColors else QuotaLightColors,
        typography = Typography(),
        content = content
    )
}
