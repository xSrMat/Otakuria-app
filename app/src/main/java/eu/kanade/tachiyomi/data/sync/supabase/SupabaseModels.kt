package eu.kanade.tachiyomi.data.sync.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SupabaseAuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: SupabaseUser? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val msg: String? = null,
)

@Serializable
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata") val userMetadata: JsonObject? = null,
) {
    val displayName: String
        get() {
            val meta = userMetadata
            if (meta != null) {
                val fullName = meta["full_name"]?.jsonPrimitive?.contentOrNull
                if (!fullName.isNullOrBlank()) return fullName
                val name = meta["name"]?.jsonPrimitive?.contentOrNull
                if (!name.isNullOrBlank()) return name
            }
            return email?.substringBefore('@')?.replaceFirstChar { it.uppercase() } ?: "Usuario"
        }

    val avatarUrl: String?
        get() {
            val meta = userMetadata ?: return null
            return meta["avatar_url"]?.jsonPrimitive?.contentOrNull
                ?: meta["picture"]?.jsonPrimitive?.contentOrNull
        }
}

@Serializable
data class CloudCategory(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CloudLibraryItem(
    val id: String, // manga_id or anime_id
    @SerialName("user_id") val userId: String,
    @SerialName("manga_data") val mangaData: JsonObject,
    val categories: List<String> = emptyList(),
    @SerialName("last_update") val lastUpdate: Long = 0L,
    @SerialName("last_chapter_read") val lastChapterRead: String? = null,
    @SerialName("last_chapter_name") val lastChapterName: String? = null,
    @SerialName("last_read_at") val lastReadAt: String? = null,
) {
    val lastChapterNumber: Double?
        get() = mangaData["lastChapterNumber"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    fun isAnime(): Boolean {
        val d = mangaData
        val mediaType = d["mediaType"]?.jsonPrimitive?.contentOrNull
        val isAnimeBool = d["isAnime"]?.jsonPrimitive?.booleanOrNull ?: false
        val hasEpisodes = d["episodes"] != null && d["episodes"] is kotlinx.serialization.json.JsonArray
        val sourceSlug = id.lowercase()
        return mediaType.equals("anime", ignoreCase = true) ||
            isAnimeBool ||
            hasEpisodes ||
            sourceSlug.contains("animeflv") ||
            sourceSlug.contains("animeav1") ||
            sourceSlug.contains("jkanime") ||
            sourceSlug.contains("animelatinohd") ||
            sourceSlug.contains("animeonlineninja") ||
            sourceSlug.contains("mundodonghua")
    }
}

@Serializable
data class CloudChapterProgress(
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("chapter_name") val chapterName: String? = null,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    @SerialName("last_read_at") val lastReadAt: String? = null,
)
