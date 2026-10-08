package eu.kanade.tachiyomi.data.sync.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SupabaseClient(
    private val preferences: SupabaseSyncPreferences,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun getBaseUrl(): String {
        return preferences.serverUrl.get().trimEnd('/')
    }

    private fun getAnonKey(): String {
        return preferences.anonKey.get().trim()
    }

    private fun getAuthToken(): String {
        val userToken = preferences.accessToken.get().trim()
        return if (userToken.isNotBlank()) userToken else getAnonKey()
    }

    /**
     * Checks if the access token is expired or close to expiring (within 2 minutes).
     * Decodes JWT payload with proper base64 padding to avoid erroneous parsing exceptions.
     */
    fun isTokenExpired(token: String): Boolean {
        if (token.isBlank()) return true
        val savedExpiry = preferences.tokenExpiresAt.get()
        val nowMs = System.currentTimeMillis()
        if (savedExpiry > 0L) {
            // Buffer of 2 minutes
            return (savedExpiry - nowMs) < 120_000L
        }

        return try {
            val parts = token.split(".")
            if (parts.size < 2) return true
            val payload = parts[1]
            val padded = when (payload.length % 4) {
                2 -> "$payload=="
                3 -> "$payload="
                else -> payload
            }
            val payloadBytes = android.util.Base64.decode(
                padded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
            val jsonStr = String(payloadBytes, Charsets.UTF_8)
            val jsonObj = json.parseToJsonElement(jsonStr) as? JsonObject
            val exp = jsonObj?.get("exp")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return false
            val nowSecs = nowMs / 1000
            (exp - nowSecs) < 120
        } catch (e: Exception) {
            // If token was set and unparseable, don't prematurely invalidate offline
            false
        }
    }

    /**
     * Refreshes the session using the saved refresh_token.
     * Retries up to 3 times on transient network failures.
     */
    suspend fun refreshSession(): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        val refreshToken = preferences.refreshToken.get().trim()
        if (refreshToken.isBlank()) {
            return@withContext Result.failure(Exception("No hay refresh_token disponible"))
        }

        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                val url = "${getBaseUrl()}/auth/v1/token?grant_type=refresh_token"
                val bodyJson = buildJsonObject {
                    put("refresh_token", refreshToken)
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody(jsonMediaType))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val authRes = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                    if (authRes.accessToken != null) {
                        preferences.accessToken.set(authRes.accessToken)
                        authRes.refreshToken?.let { preferences.refreshToken.set(it) }

                        val expSecs = authRes.expiresIn ?: 3600L
                        preferences.tokenExpiresAt.set(System.currentTimeMillis() + (expSecs * 1000L))

                        authRes.user?.let { u ->
                            preferences.userId.set(u.id)
                            u.email?.let { preferences.userEmail.set(it) }
                            preferences.userName.set(u.displayName)
                            preferences.userAvatar.set(u.avatarUrl.orEmpty())
                        }
                        logcat(LogPriority.INFO) { "Sesión de Supabase refrescada exitosamente (expira en ${expSecs}s)" }
                        return@withContext Result.success(authRes)
                    } else {
                        return@withContext Result.failure(Exception("Respuesta inválida en refresh_token"))
                    }
                } else {
                    val isPermanentAuthError = response.code in 400..401 &&
                        (responseBody.contains("invalid_grant", ignoreCase = true) ||
                         responseBody.contains("refresh_token_not_found", ignoreCase = true))

                    logcat(LogPriority.WARN) { "Error al refrescar token de Supabase (${response.code}): $responseBody" }
                    if (isPermanentAuthError) {
                        return@withContext Result.failure(Exception("invalid_grant: Sesión expirada permanentemente"))
                    }
                    lastException = Exception("Error al refrescar token (${response.code}): $responseBody")
                }
            } catch (e: Exception) {
                lastException = e
                logcat(LogPriority.WARN, e) { "Intento $attempt fallido al refrescar token de Supabase" }
            }

            if (attempt < 3) {
                delay(1000L * attempt)
            }
        }

        Result.failure(lastException ?: Exception("Fallo al refrescar sesión"))
    }

    /**
     * Guarantees a valid access token.
     * If expired, attempts refresh. If refresh fails due to a network error, returns the current token
     * so that the application does not log the user out prematurely.
     */
    suspend fun ensureValidToken(): String = withContext(Dispatchers.IO) {
        val currentToken = preferences.accessToken.get().trim()
        if (currentToken.isNotBlank() && !isTokenExpired(currentToken)) {
            return@withContext currentToken
        }

        val refreshToken = preferences.refreshToken.get().trim()
        if (refreshToken.isNotBlank()) {
            val refreshRes = refreshSession()
            if (refreshRes.isSuccess) {
                return@withContext preferences.accessToken.get().trim()
            }
        }
        return@withContext currentToken.ifBlank { getAnonKey() }
    }

    private suspend fun executeWithAuth(requestBuilder: (token: String) -> Request): okhttp3.Response = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
        var response = okHttpClient.newCall(requestBuilder(token)).execute()
        if (response.code == 401 && preferences.refreshToken.get().trim().isNotBlank()) {
            val refreshRes = refreshSession()
            if (refreshRes.isSuccess) {
                response.close()
                val newToken = preferences.accessToken.get().trim()
                response = okHttpClient.newCall(requestBuilder(newToken)).execute()
            }
        }
        response
    }

    // ---------------- AUTHENTICATION ----------------

    suspend fun signIn(email: String, pass: String): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/auth/v1/token?grant_type=password"
            val bodyJson = buildJsonObject {
                put("email", email)
                put("password", pass)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", getAnonKey())
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val authRes = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                if (authRes.accessToken != null && authRes.user != null) {
                    preferences.accessToken.set(authRes.accessToken)
                    authRes.refreshToken?.let { preferences.refreshToken.set(it) }
                    val expSecs = authRes.expiresIn ?: 3600L
                    preferences.tokenExpiresAt.set(System.currentTimeMillis() + (expSecs * 1000L))

                    preferences.userId.set(authRes.user.id)
                    preferences.userEmail.set(authRes.user.email ?: email)
                    preferences.userName.set(authRes.user.displayName)
                    preferences.userAvatar.set(authRes.user.avatarUrl.orEmpty())
                    Result.success(authRes)
                } else {
                    Result.failure(Exception("Respuesta inválida del servidor Supabase: token no encontrado"))
                }
            } else {
                val err = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                val msg = err.errorDescription ?: err.msg ?: err.error ?: "Error ${response.code}"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error en signIn de Supabase" }
            Result.failure(e)
        }
    }

    suspend fun signUp(email: String, pass: String): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/auth/v1/signup"
            val bodyJson = buildJsonObject {
                put("email", email)
                put("password", pass)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", getAnonKey())
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val authRes = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                if (authRes.accessToken != null && authRes.user != null) {
                    preferences.accessToken.set(authRes.accessToken)
                    authRes.refreshToken?.let { preferences.refreshToken.set(it) }
                    val expSecs = authRes.expiresIn ?: 3600L
                    preferences.tokenExpiresAt.set(System.currentTimeMillis() + (expSecs * 1000L))

                    preferences.userId.set(authRes.user.id)
                    preferences.userEmail.set(authRes.user.email ?: email)
                    preferences.userName.set(authRes.user.displayName)
                    preferences.userAvatar.set(authRes.user.avatarUrl.orEmpty())
                }
                Result.success(authRes)
            } else {
                val err = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                val msg = err.errorDescription ?: err.msg ?: err.error ?: "Error ${response.code}"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error en signUp de Supabase" }
            Result.failure(e)
        }
    }

    private fun generateCodeVerifier(): String {
        val secureRandom = java.security.SecureRandom()
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes, 0, bytes.size)
        val digest = messageDigest.digest()
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
    }

    fun getGoogleOAuthUrl(): String {
        val baseUrl = getBaseUrl()
        val verifier = generateCodeVerifier()
        preferences.codeVerifier.set(verifier)
        val challenge = generateCodeChallenge(verifier)
        return "$baseUrl/auth/v1/authorize?provider=google&redirect_to=otakuria://auth/callback&code_challenge=$challenge&code_challenge_method=s256"
    }

    suspend fun fetchUser(token: String): Result<SupabaseUser> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/auth/v1/user"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", getAnonKey())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val user = json.decodeFromString<SupabaseUser>(body)
                Result.success(user)
            } else {
                Result.failure(Exception("Error al consultar usuario (${response.code})"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al obtener usuario de Supabase" }
            Result.failure(e)
        }
    }

    private fun parseJwtUser(token: String): SupabaseUser? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = parts[1]
            val padded = when (payload.length % 4) {
                2 -> "$payload=="
                3 -> "$payload="
                else -> payload
            }
            val payloadBytes = android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val jsonObj = json.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)) as? JsonObject ?: return null
            val sub = jsonObj["sub"]?.jsonPrimitive?.contentOrNull ?: return null
            val email = jsonObj["email"]?.jsonPrimitive?.contentOrNull
            val userMeta = jsonObj["user_metadata"] as? JsonObject
            SupabaseUser(id = sub, email = email, userMetadata = userMeta)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveOAuthSession(accessToken: String, refreshToken: String?): Result<SupabaseUser> {
        preferences.accessToken.set(accessToken)
        if (!refreshToken.isNullOrBlank()) {
            preferences.refreshToken.set(refreshToken)
        }
        // Set default 1 hour expiry from now
        preferences.tokenExpiresAt.set(System.currentTimeMillis() + (3600L * 1000L))

        val userRes = fetchUser(accessToken)
        return if (userRes.isSuccess) {
            val user = userRes.getOrThrow()
            preferences.userId.set(user.id)
            preferences.userEmail.set(user.email.orEmpty())
            preferences.userName.set(user.displayName)
            preferences.userAvatar.set(user.avatarUrl.orEmpty())
            Result.success(user)
        } else {
            val fallback = parseJwtUser(accessToken)
            if (fallback != null) {
                preferences.userId.set(fallback.id)
                preferences.userEmail.set(fallback.email.orEmpty())
                preferences.userName.set(fallback.displayName)
                preferences.userAvatar.set(fallback.avatarUrl.orEmpty())
                Result.success(fallback)
            } else {
                userRes
            }
        }
    }

    suspend fun exchangeCodeForSession(code: String): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val verifier = preferences.codeVerifier.get()
            val url = "${getBaseUrl()}/auth/v1/token?grant_type=pkce"
            val bodyJson = buildJsonObject {
                put("auth_code", code)
                put("code_verifier", verifier)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", getAnonKey())
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val authRes = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                if (authRes.accessToken != null) {
                    preferences.accessToken.set(authRes.accessToken)
                    authRes.refreshToken?.let { preferences.refreshToken.set(it) }
                    val expSecs = authRes.expiresIn ?: 3600L
                    preferences.tokenExpiresAt.set(System.currentTimeMillis() + (expSecs * 1000L))

                    val u = authRes.user ?: parseJwtUser(authRes.accessToken)
                    if (u != null) {
                        preferences.userId.set(u.id)
                        preferences.userEmail.set(u.email ?: "")
                        preferences.userName.set(u.displayName)
                        preferences.userAvatar.set(u.avatarUrl.orEmpty())
                    }
                    Result.success(authRes)
                } else {
                    Result.failure(Exception("Respuesta inválida al canjear código"))
                }
            } else {
                val errorMsg = try {
                    val err = json.decodeFromString<SupabaseAuthResponse>(responseBody)
                    err.errorDescription ?: err.msg ?: err.error ?: "Error ${response.code}"
                } catch (e: Exception) {
                    "Error HTTP ${response.code}: $responseBody"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error en exchangeCodeForSession con PKCE" }
            Result.failure(e)
        }
    }

    // ---------------- CATEGORIES REST ----------------

    suspend fun fetchCategories(userId: String): Result<List<CloudCategory>> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/rest/v1/user_categories?user_id=eq.$userId&order=sort_order.asc"
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val categories = json.decodeFromString<List<CloudCategory>>(body)
                Result.success(categories)
            } else {
                Result.failure(Exception("Error al consultar categorías (${response.code}): $body"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al consultar categorías en Supabase" }
            Result.failure(e)
        }
    }

    suspend fun upsertCategories(categories: List<CloudCategory>): Result<Unit> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext Result.success(Unit)
        try {
            val url = "${getBaseUrl()}/rest/v1/user_categories"
            val jsonString = json.encodeToString(categories)
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonString.toRequestBody(jsonMediaType))
                    .build()
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error al guardar categorías (${response.code}): $body"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al guardar categorías en Supabase" }
            Result.failure(e)
        }
    }

    // ---------------- LIBRARY / ITEMS REST ----------------

    suspend fun fetchLibrary(userId: String): Result<List<CloudLibraryItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/rest/v1/user_library?user_id=eq.$userId&order=last_update.desc"
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val items = json.decodeFromString<List<CloudLibraryItem>>(body)
                Result.success(items)
            } else {
                Result.failure(Exception("Error al consultar biblioteca (${response.code}): $body"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al consultar biblioteca en Supabase" }
            Result.failure(e)
        }
    }

    suspend fun upsertLibraryItems(items: List<CloudLibraryItem>): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(Unit)
        try {
            val url = "${getBaseUrl()}/rest/v1/user_library?on_conflict=user_id,id"
            val jsonString = json.encodeToString(items)
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonString.toRequestBody(jsonMediaType))
                    .build()
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error al guardar biblioteca (${response.code}): $body"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al guardar biblioteca en Supabase" }
            Result.failure(e)
        }
    }

    suspend fun deleteLibraryItem(itemId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encodedId = java.net.URLEncoder.encode(itemId, "UTF-8").replace("+", "%20")
            val url = "${getBaseUrl()}/rest/v1/user_library?user_id=eq.$userId&id=eq.$encodedId"
            val resp = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .delete()
                    .build()
            }
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                val errBody = resp.body?.string().orEmpty()
                logcat(LogPriority.WARN) { "Error al eliminar item $itemId de Supabase: ${resp.code} $errBody" }
                Result.failure(Exception("Error al eliminar item: ${resp.code}"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Excepción al eliminar item $itemId de Supabase" }
            Result.failure(e)
        }
    }

    suspend fun deleteLibraryItemByTitle(title: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        deleteLibraryItemByTitleOrUrl(title, "", userId)
    }

    suspend fun deleteLibraryItemByTitleOrUrl(title: String, rawUrl: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val allItems = fetchLibrary(userId).getOrNull() ?: emptyList()
            val cleanTitle = title.trim().lowercase()
            val strippedTitle = cleanTitle.replace(Regex("[^a-zA-Z0-9]"), "")
            val cleanUrl = rawUrl.trim().lowercase().trimEnd('/')
            val slug = if (cleanUrl.contains("/")) cleanUrl.substringAfterLast("/").trim('/') else cleanUrl.trim('/')

            val matches = allItems.filter { item ->
                val itTitle = item.mangaData["title"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
                val itStripped = itTitle.replace(Regex("[^a-zA-Z0-9]"), "")
                val itUrl = item.mangaData["url"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty().trimEnd('/')
                val itSlug = if (item.id.contains("::")) item.id.substringAfter("::").lowercase() else item.id.lowercase()

                (cleanTitle.isNotEmpty() && (itTitle == cleanTitle || (strippedTitle.isNotEmpty() && itStripped == strippedTitle))) ||
                (cleanUrl.isNotEmpty() && (itUrl == cleanUrl || itUrl.contains(cleanUrl) || cleanUrl.contains(itUrl))) ||
                (slug.isNotBlank() && slug.length >= 3 && (itSlug.contains(slug) || slug.contains(itSlug) || itUrl.contains(slug)))
            }

            matches.forEach { match ->
                deleteLibraryItem(match.id, userId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLibraryItem(itemId: String, userId: String): Result<CloudLibraryItem?> = withContext(Dispatchers.IO) {
        try {
            val encodedId = java.net.URLEncoder.encode(itemId, "UTF-8").replace("+", "%20")
            val url = "${getBaseUrl()}/rest/v1/user_library?user_id=eq.$userId&id=eq.$encodedId&select=*"
            val resp = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val list = json.decodeFromString<List<CloudLibraryItem>>(body)
                Result.success(list.firstOrNull())
            } else {
                Result.failure(Exception("Error al consultar item (${resp.code}): $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLastRead(
        userId: String,
        mangaOrAnimeId: String,
        chapterOrEpId: String,
        chapterOrEpName: String?,
        chapterNumber: Double? = null,
        itemTitle: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var targetId = mangaOrAnimeId
            var existingItem = getLibraryItem(mangaOrAnimeId, userId).getOrNull()
            if (existingItem == null) {
                val allItems = fetchLibrary(userId).getOrNull() ?: emptyList()
                val cleanId = mangaOrAnimeId.lowercase()
                fun extractSlug(idOrUrl: String): String {
                    val raw = if (idOrUrl.contains("::")) idOrUrl.substringAfter("::") else idOrUrl
                    val decoded = try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
                    return decoded.trimEnd('/').substringAfterLast('/')
                        .replace(Regex("[^a-zA-Z0-9_-]"), "")
                        .lowercase()
                }
                val mySlug = extractSlug(cleanId)
                val cleanItemTitle = itemTitle?.trim()?.lowercase()

                val match = allItems.find { item ->
                    val itCleanId = item.id.lowercase()
                    if (itCleanId == cleanId) return@find true
                    val itSlug = extractSlug(itCleanId)
                    if (mySlug.isNotEmpty() && itSlug.isNotEmpty() && (mySlug == itSlug || itSlug.contains(mySlug) || mySlug.contains(itSlug))) return@find true
                    val itUrl = item.mangaData["url"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
                    if (mySlug.isNotEmpty() && itUrl.contains(mySlug)) return@find true
                    val itTitle = item.mangaData["title"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
                    if (!cleanItemTitle.isNullOrEmpty() && itTitle.isNotEmpty() && (itTitle == cleanItemTitle || itTitle.replace(Regex("[^a-z0-9]"), "") == cleanItemTitle.replace(Regex("[^a-z0-9]"), ""))) return@find true
                    false
                }
                if (match != null) {
                    targetId = match.id
                    existingItem = match
                }
            }

            val encodedId = java.net.URLEncoder.encode(targetId, "UTF-8").replace("+", "%20")
            val url = "${getBaseUrl()}/rest/v1/user_library?user_id=eq.$userId&id=eq.$encodedId"
            val nowStr = java.time.Instant.now().toString()
            val nowMs = System.currentTimeMillis()

            val existingData = existingItem?.mangaData
            val isUnmarkingAll = chapterNumber != null && chapterNumber < 0.0
            val finalNum = if (chapterNumber != null) {
                if (isUnmarkingAll) null else chapterNumber
            } else {
                existingItem?.lastChapterNumber
                    ?: existingData?.get("lastChapterNumber")?.jsonPrimitive?.content?.toDoubleOrNull()
            }

            val updatedMangaData = buildJsonObject {
                existingData?.forEach { (k, v) ->
                    if (k != "lastChapterRead" && k != "lastChapterName" && k != "lastChapterNumber" && k != "lastReadAt") {
                        put(k, v)
                    }
                }
                if (!isUnmarkingAll && chapterOrEpId.isNotBlank()) {
                    put("lastChapterRead", chapterOrEpId)
                }
                if (!isUnmarkingAll && !chapterOrEpName.isNullOrBlank()) {
                    put("lastChapterName", chapterOrEpName)
                }
                if (finalNum != null) {
                    put("lastChapterNumber", finalNum)
                }
                put("lastReadAt", nowMs)
            }

            val patchBody = buildJsonObject {
                put("last_chapter_read", if (isUnmarkingAll) null else chapterOrEpId)
                put("last_chapter_name", if (isUnmarkingAll) "" else chapterOrEpName.orEmpty())
                put("last_read_at", nowStr)
                put("last_update", nowMs)
                put("manga_data", updatedMangaData)
            }.toString()

            val resp = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .patch(patchBody.toRequestBody(jsonMediaType))
                    .build()
            }
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                val errBody = resp.body?.string().orEmpty()
                logcat(LogPriority.WARN) { "Error al actualizar last_read de $targetId: ${resp.code} $errBody" }
                Result.failure(Exception("Error al actualizar last_read: ${resp.code}"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Excepción al actualizar last_read de $mangaOrAnimeId en Supabase" }
            Result.failure(e)
        }
    }

    // ---------------- CHAPTER / EPISODE PROGRESS REST ----------------

    suspend fun fetchAllChapterProgress(userId: String): Result<List<CloudChapterProgress>> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/rest/v1/user_chapter_progress?user_id=eq.$userId"
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val list = json.decodeFromString<List<CloudChapterProgress>>(body)
                Result.success(list)
            } else {
                Result.failure(Exception("Error al consultar progreso de capítulos (${response.code})"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al obtener progreso general de capítulos de Supabase" }
            Result.failure(e)
        }
    }

    suspend fun upsertChapterProgressList(progressList: List<CloudChapterProgress>): Result<Unit> = withContext(Dispatchers.IO) {
        if (progressList.isEmpty()) return@withContext Result.success(Unit)
        try {
            val url = "${getBaseUrl()}/rest/v1/user_chapter_progress?on_conflict=user_id,manga_id,chapter_id"
            val jsonString = json.encodeToString(progressList)
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonString.toRequestBody(jsonMediaType))
                    .build()
            }
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val body = response.body?.string().orEmpty()
                Result.failure(Exception("Error al sincronizar lista de capítulos: $body"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al guardar lista de capítulos en Supabase" }
            Result.failure(e)
        }
    }

    suspend fun fetchChapterProgress(userId: String, mangaId: String): Result<List<CloudChapterProgress>> = withContext(Dispatchers.IO) {
        try {
            val encodedMangaId = java.net.URLEncoder.encode(mangaId, "UTF-8")
            val url = "${getBaseUrl()}/rest/v1/user_chapter_progress?user_id=eq.$userId&manga_id=eq.$encodedMangaId"
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val list = json.decodeFromString<List<CloudChapterProgress>>(body)
                Result.success(list)
            } else {
                Result.failure(Exception("Error al consultar progreso de capítulos (${response.code})"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al obtener progreso de capítulos de Supabase" }
            Result.failure(e)
        }
    }

    suspend fun syncChapterStatus(
        userId: String,
        mangaId: String,
        chapterId: String,
        read: Boolean,
        bookmark: Boolean = false,
        chapterName: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/rest/v1/user_chapter_progress?on_conflict=user_id,manga_id,chapter_id"
            val progressItem = CloudChapterProgress(
                userId = userId,
                mangaId = mangaId,
                chapterId = chapterId,
                chapterName = chapterName,
                read = read,
                bookmark = bookmark,
                lastReadAt = java.time.Instant.now().toString(),
            )
            val jsonString = json.encodeToString(listOf(progressItem))
            val response = executeWithAuth { token ->
                Request.Builder()
                    .url(url)
                    .addHeader("apikey", getAnonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonString.toRequestBody(jsonMediaType))
                    .build()
            }
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val body = response.body?.string().orEmpty()
                Result.failure(Exception("Error al sincronizar estado de capítulo: $body"))
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error al guardar estado de capítulo en Supabase" }
            Result.failure(e)
        }
    }
}
