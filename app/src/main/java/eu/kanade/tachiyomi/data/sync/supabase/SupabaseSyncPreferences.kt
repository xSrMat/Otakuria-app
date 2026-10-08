package eu.kanade.tachiyomi.data.sync.supabase

import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SupabaseSyncPreferences(
    preferenceStore: PreferenceStore,
) {
    val serverUrl: Preference<String> = preferenceStore.getString(
        "otakuria_sync_supabase_url",
        BuildConfig.SUPABASE_URL.ifEmpty { "https://supabaseweb.otakuria.com" },
    )

    val anonKey: Preference<String> = preferenceStore.getString(
        "otakuria_sync_supabase_anon_key",
        BuildConfig.SUPABASE_ANON_KEY,
    )

    val userEmail: Preference<String> = preferenceStore.getString(
        "otakuria_sync_user_email",
        "",
    )

    val userName: Preference<String> = preferenceStore.getString(
        "otakuria_sync_user_name",
        "",
    )

    val userAvatar: Preference<String> = preferenceStore.getString(
        "otakuria_sync_user_avatar",
        "",
    )

    val userId: Preference<String> = preferenceStore.getString(
        "otakuria_sync_user_id",
        "",
    )

    val accessToken: Preference<String> = preferenceStore.getString(
        "otakuria_sync_access_token",
        "",
    )

    val refreshToken: Preference<String> = preferenceStore.getString(
        "otakuria_sync_refresh_token",
        "",
    )

    val tokenExpiresAt: Preference<Long> = preferenceStore.getLong(
        "otakuria_sync_token_expires_at",
        0L,
    )

    val codeVerifier: Preference<String> = preferenceStore.getString(
        "otakuria_sync_code_verifier",
        "",
    )

    val autoSync: Preference<Boolean> = preferenceStore.getBoolean(
        "otakuria_sync_auto",
        true,
    )

    val lastSyncTimestamp: Preference<Long> = preferenceStore.getLong(
        "otakuria_sync_last_timestamp",
        0L,
    )

    val lastSyncStatus: Preference<String> = preferenceStore.getString(
        "otakuria_sync_last_status",
        "",
    )

    val syncedCloudIds: Preference<Set<String>> = preferenceStore.getStringSet(
        "otakuria_sync_synced_cloud_ids",
        emptySet(),
    )

    val syncedCloudAnimeIds: Preference<Set<String>> = preferenceStore.getStringSet(
        "otakuria_sync_synced_cloud_anime_ids",
        emptySet(),
    )

    fun isLoggedIn(): Boolean {
        return userId.get().isNotBlank() && (accessToken.get().isNotBlank() || refreshToken.get().isNotBlank())
    }

    fun clearSession() {
        userEmail.set("")
        userName.set("")
        userAvatar.set("")
        userId.set("")
        accessToken.set("")
        refreshToken.set("")
        tokenExpiresAt.set(0L)
        lastSyncStatus.set("")
        syncedCloudIds.set(emptySet())
        syncedCloudAnimeIds.set(emptySet())
    }
}
