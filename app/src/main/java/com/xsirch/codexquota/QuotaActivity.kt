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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    private var pendingOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QuotaTheme { App() } }
        refreshInitial()
    }

    override fun onResume() {
        super.onResume()
        if (pendingOverlayPermission && Settings.canDrawOverlays(this)) {
            pendingOverlayPermission = false
            startOverlay()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
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
                    maybeOfferOverlay()
                }
            } catch (e: Exception) {
                runOnUiThread { state.value = ScreenState.Error(e.message ?: "Falha ao entrar no ChatGPT.") }
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
                runOnUiThread { state.value = ScreenState.Error(e.message ?: "Não foi possível atualizar a cota.") }
            }
        }
    }

    private fun maybeOfferOverlay() {
        if (Settings.canDrawOverlays(this)) startOverlay()
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            startOverlay(); return
        }
        pendingOverlayPermission = true
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 830) }
        }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Overlay ativado", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Overlay desativado", Toast.LENGTH_SHORT).show()
    }

    private fun logout() {
        stopOverlay()
        AppCore.SecureStore(this).clear()
        state.value = ScreenState.SignedOut
    }

    @androidx.compose.material3.ExperimentalMaterial3Api
    @Composable
    private fun App() {
        val s = state.value
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(
                                    Brush.linearGradient(listOf(Color(0xFF57E7BE), Color(0xFF1680FF)))
                                ), contentAlignment = Alignment.Center
                            ) { Text("C", color = Color(0xFF08110E), fontWeight = FontWeight.Black, fontSize = 19.sp, fontFamily = FontFamily.Monospace) }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Cota Codex", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("overlay de uso", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (s) {
                ScreenState.SignedOut -> LoginScreen(padding)
                ScreenState.LoggingIn -> LoadingScreen(padding, "Abrindo o ChatGPT…", "Preparando login seguro com PKCE.")
                ScreenState.WaitingBrowser -> WaitingBrowserScreen(padding)
                ScreenState.Loading -> LoadingScreen(padding, "Atualizando sua cota…", "Consultando os limites atuais do Codex.")
                is ScreenState.Ready -> Dashboard(padding, s.usage)
                is ScreenState.Error -> ErrorScreen(padding, s.message)
            }
        }
    }

    @Composable
    private fun LoginScreen(padding: PaddingValues) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Text("Sua cota sempre\nno canto da tela.", fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 41.sp)
                Spacer(Modifier.height(12.dp))
                Text("Uma bolha flutuante que expande ao toque, atualiza sua cota e mostra exatamente quando cada janela reseta.", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
            }
            item {
                PremiumCard {
                    Feature(Icons.Rounded.TouchApp, "Overlay retrátil", "Arraste para qualquer canto. Toque para expandir ou recolher.")
                    HorizontalDivider(Modifier.padding(vertical = 15.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Feature(Icons.Rounded.Login, "Login direto", "Abre o ChatGPT no navegador e retorna automaticamente ao app. Sem código de dispositivo.")
                    HorizontalDivider(Modifier.padding(vertical = 15.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Feature(Icons.Rounded.Security, "Sem senha no app", "A autenticação acontece no domínio da OpenAI usando OAuth + PKCE.")
                }
            }
            item {
                Button(
                    onClick = { directLogin() },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF57E7BE), contentColor = Color(0xFF062E24))
                ) {
                    Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(10.dp)); Text("Entrar com ChatGPT", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun WaitingBrowserScreen(padding: PaddingValues) {
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF14352D), modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.OpenInNew, null, tint = Color(0xFF57E7BE), modifier = Modifier.size(34.dp)) }
                }
                Spacer(Modifier.height(22.dp))
                Text("Conclua o login no navegador", fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(9.dp))
                Text("Não há código para copiar. Assim que o ChatGPT autorizar, o app recebe o retorno automaticamente.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 21.sp)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(color = Color(0xFF57E7BE), strokeWidth = 3.dp)
            }
        }
    }

    @Composable
    private fun Dashboard(padding: PaddingValues, usage: AppCore.UsageData) {
        val overlayOn = Settings.canDrawOverlays(this)
        val primary = usage.windows.firstOrNull { it.group == "Codex" }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("${usage.plan.ifBlank { "Codex" }} · atualizado agora", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text("Visão geral", fontWeight = FontWeight.Bold, fontSize = 27.sp)
                    }
                    TextButton(onClick = { loadUsage() }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(5.dp)); Text("Atualizar") }
                }
            }
            if (primary != null) {
                item {
                    PremiumCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("COTA PRINCIPAL", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("${primary.remainingPercent.toInt()}%", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 42.sp, color = quotaColor(primary.remainingPercent))
                                Text("restante · ${AppCore.windowLabel(primary.windowSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Surface(shape = CircleShape, color = quotaColor(primary.remainingPercent).copy(alpha = .12f), modifier = Modifier.size(68.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, tint = quotaColor(primary.remainingPercent), modifier = Modifier.size(30.dp)) }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = (primary.remainingPercent / 100).toFloat(), modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = quotaColor(primary.remainingPercent), trackColor = Color.White.copy(alpha = .08f))
                        Spacer(Modifier.height(12.dp))
                        Text(resetText(primary.resetAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            item {
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = if (overlayOn) Color(0xFF14352D) else Color(0xFF20262B), modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Visibility, null, tint = if (overlayOn) Color(0xFF57E7BE) else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (overlayOn) "Overlay autorizado" else "Ativar overlay", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(if (overlayOn) "A bolha pode aparecer sobre outros apps." else "Permita exibir a bolha sobre outros aplicativos.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    if (overlayOn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { startOverlay() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Mostrar bolha") }
                            OutlinedButton(onClick = { stopOverlay() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Ocultar") }
                        }
                    } else {
                        Button(onClick = { requestOverlay() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Conceder permissão") }
                    }
                }
            }
            if (usage.windows.size > 1) {
                item { Text("Outras janelas", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp)) }
                items(usage.windows.drop(1)) { window -> WindowCard(window) }
            }
            item {
                TextButton(onClick = { logout() }, modifier = Modifier.fillMaxWidth()) { Text("Sair da conta", color = Color(0xFFFF8A92)) }
            }
        }
    }

    @Composable
    private fun WindowCard(w: AppCore.QuotaWindow) {
        PremiumCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(w.group, fontWeight = FontWeight.SemiBold); Text(AppCore.windowLabel(w.windowSeconds), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                Text("${w.remainingPercent.toInt()}%", color = quotaColor(w.remainingPercent), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(11.dp))
            LinearProgressIndicator(progress = (w.remainingPercent / 100).toFloat(), modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = quotaColor(w.remainingPercent), trackColor = Color.White.copy(alpha = .08f))
            Spacer(Modifier.height(9.dp)); Text(resetText(w.resetAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }

    @Composable
    private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        Row(verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color(0xFF57E7BE)) }
            }
            Spacer(Modifier.width(13.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(3.dp)); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp) }
        }
    }

    @Composable
    private fun LoadingScreen(padding: PaddingValues, title: String, body: String) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF57E7BE), strokeWidth = 3.dp)
                Spacer(Modifier.height(18.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(Modifier.height(6.dp)); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun ErrorScreen(padding: PaddingValues, message: String) {
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF727C), modifier = Modifier.size(48.dp)); Spacer(Modifier.height(16.dp))
                Text("Não foi possível continuar", fontWeight = FontWeight.Bold, fontSize = 21.sp); Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 20.sp); Spacer(Modifier.height(18.dp))
                Button(onClick = { refreshInitial() }, shape = RoundedCornerShape(14.dp)) { Text("Tentar novamente") }
                TextButton(onClick = { AppCore.SecureStore(this@QuotaActivity).clear(); state.value = ScreenState.SignedOut }) { Text("Voltar ao login") }
            }
        }
    }

    @Composable
    private fun PremiumCard(content: @Composable ColumnScope.() -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(18.dp), content = content)
        }
    }

    private fun resetText(unixSeconds: Long): String {
        if (unixSeconds <= 0) return "Reset indisponível"
        val diff = unixSeconds * 1000L - System.currentTimeMillis()
        val mins = diff.coerceAtLeast(0L) / 60_000L; val days = mins / 1440; val hours = (mins % 1440) / 60; val min = mins % 60
        val relative = when { days > 0 -> "${days}d ${hours}h"; hours > 0 -> "${hours}h ${min}min"; else -> "${min.coerceAtLeast(1)}min" }
        val absolute = DateTimeFormatter.ofPattern("dd/MM · HH:mm", Locale("pt", "BR")).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(unixSeconds))
        return "Renova em $relative · $absolute"
    }

    @Composable
    private fun quotaColor(v: Double): Color = when { v >= 50 -> Color(0xFF57E7BE); v >= 20 -> Color(0xFFFFC857); else -> Color(0xFFFF727C) }

    private sealed interface ScreenState {
        data object SignedOut : ScreenState
        data object LoggingIn : ScreenState
        data object WaitingBrowser : ScreenState
        data object Loading : ScreenState
        data class Ready(val usage: AppCore.UsageData) : ScreenState
        data class Error(val message: String) : ScreenState
    }
}

private val QuotaColors = darkColorScheme(
    primary = Color(0xFF57E7BE), onPrimary = Color(0xFF062E24),
    background = Color(0xFF090B0D), onBackground = Color(0xFFF2F5F4),
    surface = Color(0xFF111519), onSurface = Color(0xFFF2F5F4),
    surfaceVariant = Color(0xFF1A2025), onSurfaceVariant = Color(0xFFA7B0B7),
    outline = Color(0xFF3A434A), outlineVariant = Color(0xFF252C32), error = Color(0xFFFF727C)
)

@Composable
private fun QuotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = QuotaColors, typography = Typography(), content = content)
}
