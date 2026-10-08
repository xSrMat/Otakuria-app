package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager
import eu.kanade.tachiyomi.data.sync.supabase.SyncState
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OtakuriaSyncScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        val syncManager = remember { Injekt.get<SupabaseSyncManager>() }
        val preferences = syncManager.preferences
        val client = syncManager.client

        val userEmail by preferences.userEmail.collectAsState()
        val userId by preferences.userId.collectAsState()
        val autoSync by preferences.autoSync.collectAsState()
        val lastSyncTimestamp by preferences.lastSyncTimestamp.collectAsState()
        val lastSyncStatus by preferences.lastSyncStatus.collectAsState()
        val syncState by syncManager.syncState.collectAsState()

        val isLoggedIn = userId.isNotBlank()

        var authErrorMessage by remember { mutableStateOf<String?>(null) }
        var showGoogleAuthDialog by remember { mutableStateOf(false) }

        var showAdvancedSettings by remember { mutableStateOf(false) }
        var serverUrlInput by remember { mutableStateOf(preferences.serverUrl.get()) }
        var anonKeyInput by remember { mutableStateOf(preferences.anonKey.get()) }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Otakuria Cloud Sync",
                    navigateUp = { navigator.pop() },
                    navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header Banner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sincronización en la Nube",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Sincroniza mangas, animes y tu progreso entre la app y la web",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 1. Account Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Cuenta de Otakuria",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        if (isLoggedIn) {
                            val userName by preferences.userName.collectAsState()
                            val userAvatar by preferences.userAvatar.collectAsState()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (userAvatar.isNotBlank()) {
                                    AsyncImage(
                                        model = userAvatar,
                                        contentDescription = "Avatar",
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = userName.ifBlank { userEmail.substringBefore('@') },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = userEmail.ifBlank { "Cuenta conectada" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    preferences.clearSession()
                                    context.toast("Sesión cerrada")
                                },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Cerrar sesión")
                            }
                        } else {
                            Text(
                                text = "Inicia sesión con tu cuenta de Google para sincronizar tus animes, mangas y lectura con otakuria.com.",
                                style = MaterialTheme.typography.bodySmall,
                            )

                            // Botón principal de Google
                            Button(
                                onClick = {
                                    authErrorMessage = null
                                    showGoogleAuthDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_google),
                                    contentDescription = "Google",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Continuar con Google",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            // Alternativa: abrir navegador externo (Chrome)
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            OutlinedButton(
                                onClick = {
                                    authErrorMessage = null
                                    try {
                                        uriHandler.openUri(client.getGoogleOAuthUrl())
                                        context.toast("Inicia sesión en el navegador; serás redirigido de vuelta")
                                    } catch (e: Exception) {
                                        authErrorMessage = "No se pudo abrir el navegador: ${e.message}"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = "Abrir login en navegador externo (Chrome)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            authErrorMessage?.let { err ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Synchronization Actions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Acciones de Sincronización",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sincronización automática",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Sincroniza al abrir la aplicación",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoSync,
                                onCheckedChange = { preferences.autoSync.set(it) },
                            )
                        }

                        // Status Info
                        if (lastSyncTimestamp > 0L) {
                            val formattedDate = remember(lastSyncTimestamp) {
                                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                                    .format(Date(lastSyncTimestamp))
                            }
                            Text(
                                text = "Última sincronización: $formattedDate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (lastSyncStatus.isNotBlank()) {
                            Text(
                                text = lastSyncStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lastSyncStatus.startsWith("Error")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }

                        // Sync button & State
                        when (val currentSyncState = syncState) {
                            is SyncState.Syncing -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = currentSyncState.message,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            else -> {
                                Button(
                                    onClick = {
                                        syncManager.triggerSync(force = true)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = isLoggedIn,
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sincronizar ahora")
                                }
                            }
                        }
                    }
                }

                // 3. Advanced Server Config
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Servidor Supabase",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(onClick = { showAdvancedSettings = !showAdvancedSettings }) {
                                Text(
                                    text = if (showAdvancedSettings) "Ocultar" else "Editar",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        if (showAdvancedSettings) {
                            OutlinedTextField(
                                value = serverUrlInput,
                                onValueChange = { serverUrlInput = it },
                                label = { Text("Supabase URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )

                            OutlinedTextField(
                                value = anonKeyInput,
                                onValueChange = { anonKeyInput = it },
                                label = { Text("Supabase Anon Key") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        preferences.serverUrl.set(serverUrlInput.trim())
                                        preferences.anonKey.set(anonKeyInput.trim())
                                        context.toast("Configuración guardada")
                                        showAdvancedSettings = false
                                    },
                                ) {
                                    Text("Guardar servidor")
                                }
                            }
                        } else {
                            Text(
                                text = "URL: ${preferences.serverUrl.get()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showGoogleAuthDialog) {
            Dialog(
                onDismissRequest = { showGoogleAuthDialog = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AppBar(
                            title = "Iniciar sesión con Google",
                            navigateUp = { showGoogleAuthDialog = false },
                            navigationIcon = Icons.Outlined.Close,
                        )

                        var isWebLoading by remember { mutableStateOf(true) }
                        if (isWebLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    )

                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        setSupportMultipleWindows(false)
                                        javaScriptCanOpenWindowsAutomatically = true

                                        val defaultUa = userAgentString
                                        userAgentString = defaultUa.replace("; wv", "")
                                            .ifEmpty { "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36" }
                                    }

                                    fun checkUrl(url: String?): Boolean {
                                        if (url == null) return false
                                        val raw = url

                                        if (raw.contains("/auth/v1/")) {
                                            return false
                                        }

                                        val uri = android.net.Uri.parse(url)
                                        val fragment = uri.fragment.orEmpty()
                                        val query = uri.query.orEmpty()

                                        val hasAccessToken = raw.contains("access_token=")
                                        val hasCode = uri.getQueryParameter("code") != null || raw.contains("code=")
                                        val hasError = raw.contains("error_description=") || raw.contains("error=")

                                        val isRedirect = raw.startsWith("otakuria://") ||
                                            (raw.contains("otakuria.com") && !raw.contains("supabaseweb.otakuria.com"))

                                        if (isRedirect && (hasAccessToken || hasCode || hasError)) {
                                            stopLoading()
                                            if (hasAccessToken) {
                                                val paramString = if (fragment.contains("access_token=")) {
                                                    fragment
                                                } else if (query.contains("access_token=")) {
                                                    query
                                                } else if (raw.contains("#")) {
                                                    raw.substringAfter("#")
                                                } else {
                                                    raw.substringAfter("?")
                                                }
                                                val params = paramString.split("&").associate {
                                                    val parts = it.split("=", limit = 2)
                                                    if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else parts[0] to ""
                                                }
                                                val accessToken = params["access_token"] ?: ""
                                                val refreshToken = params["refresh_token"]
                                                if (accessToken.isNotBlank()) {
                                                    coroutineScope.launch {
                                                        val res = client.saveOAuthSession(accessToken, refreshToken)
                                                        showGoogleAuthDialog = false
                                                        if (res.isSuccess) {
                                                            context.toast("¡Bienvenido! Sesión iniciada con Google")
                                                            syncManager.triggerSync(force = true)
                                                        } else {
                                                            authErrorMessage = "Error al autenticar: ${res.exceptionOrNull()?.message}"
                                                        }
                                                    }
                                                    return true
                                                }
                                            } else if (hasCode) {
                                                val code = uri.getQueryParameter("code") ?: raw.substringAfter("code=").substringBefore("&")
                                                if (code.isNotBlank()) {
                                                    coroutineScope.launch {
                                                        val res = client.exchangeCodeForSession(code)
                                                        showGoogleAuthDialog = false
                                                        if (res.isSuccess) {
                                                            context.toast("¡Bienvenido! Sesión iniciada con Google")
                                                            syncManager.triggerSync(force = true)
                                                        } else {
                                                            authErrorMessage = "Error al canjear código: ${res.exceptionOrNull()?.message}"
                                                        }
                                                    }
                                                    return true
                                                }
                                            } else if (hasError) {
                                                val desc = uri.getQueryParameter("error_description") ?: "Error al autenticar con Google"
                                                authErrorMessage = desc
                                                showGoogleAuthDialog = false
                                                return true
                                            }
                                        }
                                        return false
                                    }

                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: android.webkit.WebView?,
                                            request: android.webkit.WebResourceRequest?,
                                        ): Boolean {
                                            val u = request?.url?.toString()
                                            if (checkUrl(u)) return true
                                            return super.shouldOverrideUrlLoading(view, request)
                                        }

                                        override fun onPageStarted(
                                            view: android.webkit.WebView?,
                                            url: String?,
                                            favicon: android.graphics.Bitmap?,
                                        ) {
                                            if (checkUrl(url)) return
                                            isWebLoading = true
                                            super.onPageStarted(view, url, favicon)
                                        }

                                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                            if (checkUrl(url)) return
                                            isWebLoading = false
                                            super.onPageFinished(view, url)
                                        }

                                        override fun doUpdateVisitedHistory(
                                            view: android.webkit.WebView?,
                                            url: String?,
                                            isReload: Boolean,
                                        ) {
                                            checkUrl(url)
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                        }
                                    }

                                    loadUrl(client.getGoogleOAuthUrl())
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
