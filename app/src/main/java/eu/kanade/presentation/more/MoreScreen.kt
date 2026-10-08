package eu.kanade.presentation.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.data.sync.supabase.SupabaseSyncManager
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    navStyle: NavStyle,
    onClickAccount: () -> Unit,
    onClickAlt: () -> Unit,
    onClickDownloadQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickStorage: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickPlayerSettings: () -> Unit,
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val syncManager = remember { Injekt.get<SupabaseSyncManager>() }
    val preferences = syncManager.preferences
    val client = syncManager.client

    val userId by preferences.userId.collectAsState()
    val userEmail by preferences.userEmail.collectAsState()
    val userName by preferences.userName.collectAsState()
    val userAvatar by preferences.userAvatar.collectAsState()
    val isLoggedIn = userId.isNotBlank()

    var showGoogleAuthDialog by remember { mutableStateOf(false) }

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(
            modifier = Modifier.padding(contentPadding),
        ) {
            item {
                LogoHeader(
                    iconPadding = PaddingValues(vertical = 24.dp),
                )
            }

            // ---------------- SECCIÓN CUENTA OTAKURIA ----------------
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Cuenta",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClickAccount() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (isLoggedIn) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (userAvatar.isNotBlank()) {
                                    AsyncImage(
                                        model = userAvatar,
                                        contentDescription = "Avatar",
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(50.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = userName.ifBlank { userEmail.substringBefore('@') },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = userEmail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Conectado con Otakuria Cloud",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                IconButton(onClick = onClickAccount) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudSync,
                                        contentDescription = "Sincronización",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(38.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Inicia sesión en Otakuria",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "Sincroniza tus animes, mangas y lecturas",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Button(
                                    onClick = { showGoogleAuthDialog = true },
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
                                        text = "Iniciar sesión con Google",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.label_downloaded_only),
                    subtitle = stringResource(MR.strings.downloaded_only_summary),
                    icon = Icons.Outlined.CloudOff,
                    checked = downloadedOnly,
                    onCheckedChanged = onDownloadedOnlyChange,
                )
            }
            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.pref_incognito_mode),
                    subtitle = stringResource(AYMR.strings.pref_incognito_mode_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                    checked = incognitoMode,
                    onCheckedChanged = onIncognitoModeChange,
                )
            }

            item { HorizontalDivider() }

            item {
                TextPreferenceWidget(
                    title = navStyle.moreTab.options.title,
                    icon = navStyle.moreIcon,
                    onPreferenceClick = onClickAlt,
                )
            }

            item {
                val downloadQueueState = downloadQueueStateProvider()
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_download_queue),
                    subtitle = when (downloadQueueState) {
                        DownloadQueueState.Stopped -> null
                        is DownloadQueueState.Paused -> {
                            val pending = downloadQueueState.pending
                            if (pending == 0) {
                                stringResource(MR.strings.paused)
                            } else {
                                "${stringResource(MR.strings.paused)} • ${
                                    pluralStringResource(
                                        MR.plurals.download_queue_summary,
                                        count = pending,
                                        pending,
                                    )
                                }"
                            }
                        }

                        is DownloadQueueState.Downloading -> {
                            val pending = downloadQueueState.pending
                            pluralStringResource(
                                MR.plurals.download_queue_summary,
                                count = pending,
                                pending,
                            )
                        }
                    },
                    icon = Icons.Outlined.GetApp,
                    onPreferenceClick = onClickDownloadQueue,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(AYMR.strings.general_categories),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    onPreferenceClick = onClickCategories,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_stats),
                    icon = Icons.Outlined.QueryStats,
                    onPreferenceClick = onClickStats,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_data_storage),
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onClickDataAndStorage,
                )
            }

            item { HorizontalDivider() }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_settings),
                    icon = Icons.Outlined.Settings,
                    onPreferenceClick = onClickSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(AYMR.strings.label_player_settings),
                    icon = Icons.Outlined.VideoSettings,
                    onPreferenceClick = onClickPlayerSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_category_about),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onClickAbout,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_help),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    onPreferenceClick = { uriHandler.openUri(Constants.URL_HELP) },
                )
            }
        }
    }

    if (showGoogleAuthDialog) {
        Dialog(
            onDismissRequest = { showGoogleAuthDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
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
                                                        context.toast("Error al autenticar: ${res.exceptionOrNull()?.message}")
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
                                                        context.toast("Error al canjear código: ${res.exceptionOrNull()?.message}")
                                                    }
                                                }
                                                return true
                                            }
                                        } else if (hasError) {
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
