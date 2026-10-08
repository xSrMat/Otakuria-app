package eu.kanade.tachiyomi.data.sync.supabase

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.GetMangaFavorites
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.mi.data.AnimeDatabase
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.domain.source.manga.model.StubMangaSource
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val message: String) : SyncState()
    data class Success(val message: String, val timestamp: Long) : SyncState()
    data class Error(val message: String) : SyncState()
}

class SupabaseSyncManager(
    val preferences: SupabaseSyncPreferences = Injekt.get(),
    val client: SupabaseClient = Injekt.get(),
    val mangaDatabase: Database = Injekt.get(),
    val animeDatabase: AnimeDatabase = Injekt.get(),
    val getMangaCategories: GetMangaCategories = Injekt.get(),
    val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    val getMangaFavorites: GetMangaFavorites = Injekt.get(),
    val getAnimeFavorites: GetAnimeFavorites = Injekt.get(),
    val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    val getAnimeByUrlAndSourceId: GetAnimeByUrlAndSourceId = Injekt.get(),
    val mangaRepository: MangaRepository = Injekt.get(),
    val animeRepository: AnimeRepository = Injekt.get(),
    val chapterRepository: ChapterRepository = Injekt.get(),
    val episodeRepository: EpisodeRepository = Injekt.get(),
    val mangaSourceManager: MangaSourceManager = Injekt.get(),
    val animeSourceManager: AnimeSourceManager = Injekt.get(),
    val mangaExtensionManager: eu.kanade.tachiyomi.extension.manga.MangaExtensionManager = Injekt.get(),
    val animeExtensionManager: eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager = Injekt.get(),
    val mangaExtensionRepoRepository: MangaExtensionRepoRepository = Injekt.get(),
    val animeExtensionStoreRepository: AnimeExtensionStoreRepository = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var lastTriggerTime = 0L

    fun triggerSync(force: Boolean = false) {
        if (!preferences.isLoggedIn()) {
            _syncState.value = SyncState.Error("Debes iniciar sesión con tu cuenta de Otakuria / Supabase")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && (now - lastTriggerTime < 5000L)) {
            return
        }
        lastTriggerTime = now
        scope.launch {
            runSync()
        }
    }

    suspend fun runSync(): Boolean = withContext(Dispatchers.IO) {
        val userId = preferences.userId.get().trim()
        if (userId.isBlank() || !preferences.isLoggedIn()) {
            _syncState.value = SyncState.Error("No hay sesión activa de Otakuria")
            return@withContext false
        }

        try {
            _syncState.value = SyncState.Syncing("Verificando sesión...")
            client.ensureValidToken()

            val accessToken = preferences.accessToken.get().trim()
            val refreshToken = preferences.refreshToken.get().trim()

            if (accessToken.isBlank() && refreshToken.isBlank()) {
                val err = "Tu sesión ha expirado. Vuelve a iniciar sesión con Google."
                preferences.lastSyncStatus.set("Error: $err")
                _syncState.value = SyncState.Error(err)
                return@withContext false
            }

            _syncState.value = SyncState.Syncing("Reparando fuentes de la biblioteca...")
            autoRepairLocalSources()

            _syncState.value = SyncState.Syncing("Sincronizando categorías...")
            syncCategories(userId)

            _syncState.value = SyncState.Syncing("Consultando biblioteca...")
            val cloudItems = client.fetchLibrary(userId).getOrElse { emptyList() }

            _syncState.value = SyncState.Syncing("Sincronizando mangas...")
            val mangaCount = syncMangaLibrary(userId, cloudItems.filter { !it.isAnime() })

            _syncState.value = SyncState.Syncing("Sincronizando animes...")
            val animeCount = syncAnimeLibrary(userId, cloudItems.filter { it.isAnime() })

            val now = System.currentTimeMillis()
            preferences.lastSyncTimestamp.set(now)
            val summary = "Sincronización completada ($mangaCount mangas, $animeCount animes)"
            preferences.lastSyncStatus.set(summary)
            _syncState.value = SyncState.Success(summary, now)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error en la sincronización de Otakuria" }
            val rawMsg = e.localizedMessage ?: "Error de sincronización"
            val errMsg = if (rawMsg.contains("Unable to resolve host", ignoreCase = true) ||
                rawMsg.contains("timeout", ignoreCase = true) ||
                rawMsg.contains("Failed to connect", ignoreCase = true)
            ) {
                "Sin conexión a internet (sincronización pausada)"
            } else {
                rawMsg
            }
            preferences.lastSyncStatus.set("Error: $errMsg")
            _syncState.value = SyncState.Error(errMsg)
            false
        }
    }

    // ---------------- CATEGORIES SYNC ----------------

    private suspend fun syncCategories(userId: String) {
        val cloudCats = client.fetchCategories(userId).getOrElse { emptyList() }
        val cloudCatsByName = cloudCats.associateBy { it.name.trim().lowercase() }

        // Categorías Manga
        val localMangaCats = getMangaCategories.await()
        val localMangaCatsByName = localMangaCats.associateBy { it.name.trim().lowercase() }
        var nextMangaOrder = (localMangaCats.maxOfOrNull { it.order } ?: 0L) + 1L

        cloudCats.forEach { cloudCat ->
            val cleanName = cloudCat.name.trim()
            if (cleanName.isNotBlank() && !localMangaCatsByName.containsKey(cleanName.lowercase())) {
                try {
                    mangaDatabase.categoriesQueries.insert(cleanName, nextMangaOrder++, 0L)
                } catch (ignored: Exception) {
                }
            }
        }

        // Categorías Anime
        val localAnimeCats = getAnimeCategories.await()
        val localAnimeCatsByName = localAnimeCats.associateBy { it.name.trim().lowercase() }
        var nextAnimeOrder = (localAnimeCats.maxOfOrNull { it.order } ?: 0L) + 1L

        cloudCats.forEach { cloudCat ->
            val cleanName = cloudCat.name.trim()
            if (cleanName.isNotBlank() && !localAnimeCatsByName.containsKey(cleanName.lowercase())) {
                try {
                    animeDatabase.categoriesQueries.insert(cleanName, nextAnimeOrder++, 0L)
                } catch (ignored: Exception) {
                }
            }
        }

        // Subir categorías locales a Supabase
        val toUploadToCloud = mutableListOf<CloudCategory>()
        getMangaCategories.await().forEach { cat ->
            val cleanName = cat.name.trim()
            if (cat.id > 0L && cleanName.isNotBlank() && !cloudCatsByName.containsKey(cleanName.lowercase())) {
                toUploadToCloud.add(
                    CloudCategory(
                        id = "cat_manga_${cat.id}_${UUID.randomUUID().toString().take(8)}",
                        userId = userId,
                        name = cleanName,
                        sortOrder = cat.order.toInt(),
                    ),
                )
            }
        }
        getAnimeCategories.await().forEach { cat ->
            val cleanName = cat.name.trim()
            if (cat.id > 0L && cleanName.isNotBlank() && !cloudCatsByName.containsKey(cleanName.lowercase()) &&
                toUploadToCloud.none { it.name.equals(cleanName, ignoreCase = true) }
            ) {
                toUploadToCloud.add(
                    CloudCategory(
                        id = "cat_anime_${cat.id}_${UUID.randomUUID().toString().take(8)}",
                        userId = userId,
                        name = cleanName,
                        sortOrder = cat.order.toInt(),
                    ),
                )
            }
        }

        if (toUploadToCloud.isNotEmpty()) {
            client.upsertCategories(toUploadToCloud)
        }
    }

    // ---------------- MANGA LIBRARY SYNC ----------------

    private val mangaToCloudIdMap = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val animeToCloudIdMap = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private suspend fun syncMangaLibrary(
        userId: String,
        cloudMangaItems: List<CloudLibraryItem>,
    ): Int {
        // 0. Deduplicar base de datos local de manga
        val rawLocalFavorites = getMangaFavorites.await()
        val seenLocalTitles = mutableSetOf<String>()
        rawLocalFavorites.forEach { fav ->
            val cleanTitle = fav.title.trim().lowercase()
            if (!seenLocalTitles.add(cleanTitle)) {
                logcat(LogPriority.INFO) { "Eliminando duplicado local de manga: ${fav.title} (id: ${fav.id})" }
                mangaRepository.updateManga(MangaUpdate(id = fav.id, favorite = false))
            }
        }

        // 0.5. Deduplicar items de la nube y eliminar IDs redundantes
        val dedupedCloudManga = mutableListOf<CloudLibraryItem>()
        val seenCloudKeys = mutableSetOf<String>()
        val cloudIdsToDelete = mutableListOf<String>()

        cloudMangaItems.forEach { item ->
            val title = item.mangaData["title"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val path = cleanRelativePath(item.mangaData["path"]?.jsonPrimitive?.content ?: item.mangaData["url"]?.jsonPrimitive?.content.orEmpty()).lowercase()
            val key = if (title.isNotEmpty()) "title_$title" else if (path.isNotEmpty()) "path_$path" else item.id

            if (seenCloudKeys.add(key)) {
                dedupedCloudManga.add(item)
            } else {
                cloudIdsToDelete.add(item.id)
            }
        }
        cloudIdsToDelete.forEach { dupId ->
            client.deleteLibraryItem(dupId, userId)
        }

        val localFavorites = getMangaFavorites.await()
        val localFavoritesByUrl = localFavorites.associateBy { it.url }

        val localCategories = getMangaCategories.await()
        val localCategoriesByName = localCategories.associateBy { it.name.trim().lowercase() }

        val previousSyncedIds = preferences.syncedCloudIds.get()

        // 1. Verificar eliminaciones locales de manga
        localFavorites.forEach { localManga ->
            val pkgName = mangaExtensionManager.getExtensionPackage(localManga.source)
            val source = mangaSourceManager.get(localManga.source)
            val sourceName = source?.name ?: "Unknown"
            val baseUrl = (source as? HttpSource)?.baseUrl.orEmpty().trimEnd('/')
            val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val sourceExtId = if (!pkgName.isNullOrBlank()) "ext_$pkgName" else "ext_$sourceSlug"
            val fullUrl = if (localManga.url.startsWith("http://") || localManga.url.startsWith("https://")) {
                localManga.url
            } else if (baseUrl.isNotEmpty()) {
                "$baseUrl${if (localManga.url.startsWith("/")) "" else "/"}${localManga.url}"
            } else {
                localManga.url
            }
            val encodedUrl = URLEncoder.encode(fullUrl, "UTF-8")
            val defaultCloudId = "$sourceExtId::$encodedUrl"

            val isPresentInCloud = dedupedCloudManga.any { item ->
                item.id == defaultCloudId ||
                    item.id.endsWith("::$encodedUrl") ||
                    item.mangaData["url"]?.jsonPrimitive?.content == fullUrl ||
                    item.mangaData["title"]?.jsonPrimitive?.content?.equals(localManga.title, ignoreCase = true) == true
            }

            if (!isPresentInCloud) {
                val wasPreviouslySynced = previousSyncedIds.contains(defaultCloudId) ||
                    previousSyncedIds.contains(fullUrl) ||
                    previousSyncedIds.contains(localManga.url)

                if (wasPreviouslySynced) {
                    logcat(LogPriority.INFO) { "Eliminando favorito de manga local por eliminación en la nube: ${localManga.title}" }
                    mangaRepository.updateManga(MangaUpdate(id = localManga.id, favorite = false, dateAdded = 0L))
                }
            }
        }

        // 2. Nube -> Local: Importar mangas de Supabase (deduplicados)
        val importedLocalMangaTitles = mutableSetOf<String>()
        dedupedCloudManga.forEach { cloudItem ->
            try {
                val dataObj = cloudItem.mangaData
                val title = dataObj["title"]?.jsonPrimitive?.content ?: "Sin título"
                if (importedLocalMangaTitles.contains(title.trim().lowercase())) {
                    return@forEach
                }
                importedLocalMangaTitles.add(title.trim().lowercase())

                val rawUrl = dataObj["url"]?.jsonPrimitive?.content ?: ""
                val pathUrl = dataObj["path"]?.jsonPrimitive?.content
                val sourceIdRaw = dataObj["sourceNumericId"]?.jsonPrimitive?.content
                    ?: dataObj["sourceId"]?.jsonPrimitive?.content ?: ""
                val sourceName = dataObj["sourceName"]?.jsonPrimitive?.content

                val effectiveUrl = if (!pathUrl.isNullOrBlank()) pathUrl else rawUrl
                val (sourceId, url) = resolveMangaSource(sourceIdRaw, effectiveUrl, sourceName)
                val cleanUrl = cleanRelativePath(url)

                val artist = dataObj["artist"]?.jsonPrimitive?.content
                val author = dataObj["author"]?.jsonPrimitive?.content
                val desc = dataObj["description"]?.jsonPrimitive?.content
                val thumb = dataObj["thumbnailUrl"]?.jsonPrimitive?.content
                val statusStr = dataObj["status"]?.jsonPrimitive?.content ?: "UNKNOWN"
                val statusLong = mapStatusStringToLong(statusStr)

                val genresList = try {
                    dataObj["genre"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (cleanUrl.isNotBlank()) {
                    val existingManga = getMangaByUrlAndSourceId.await(cleanUrl, sourceId)
                        ?: localFavoritesByUrl[cleanUrl]
                        ?: localFavoritesByUrl[url]
                        ?: localFavoritesByUrl[rawUrl]
                        ?: localFavorites.firstOrNull { it.title.equals(title, ignoreCase = true) }

                    val mangaId = if (existingManga != null) {
                        mangaRepository.updateManga(
                            MangaUpdate(
                                id = existingManga.id,
                                source = sourceId,
                                url = cleanUrl,
                                favorite = true,
                                title = title,
                                artist = artist ?: existingManga.artist,
                                author = author ?: existingManga.author,
                                description = desc ?: existingManga.description,
                                genre = genresList.ifEmpty { existingManga.genre.orEmpty() },
                                status = statusLong,
                                thumbnailUrl = thumb ?: existingManga.thumbnailUrl,
                                lastUpdate = cloudItem.lastUpdate.takeIf { it > 0 } ?: existingManga.lastUpdate,
                            ),
                        )
                        existingManga.id
                    } else {
                        val newManga = Manga.create().copy(
                            source = sourceId,
                            url = cleanUrl,
                            artist = artist,
                            author = author,
                            description = desc,
                            genre = genresList,
                            title = title,
                            status = statusLong,
                            thumbnailUrl = thumb,
                            favorite = true,
                            lastUpdate = cloudItem.lastUpdate.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            dateAdded = System.currentTimeMillis(),
                        )
                        mangaRepository.insertManga(newManga) ?: 0L
                    }

                    if (mangaId > 0L) {
                        mangaToCloudIdMap[mangaId] = cloudItem.id
                    }

                    // Categorías del manga
                    val catNames = cloudItem.categories
                    if (catNames.isNotEmpty() && mangaId > 0L) {
                        val catIds = catNames.mapNotNull { localCategoriesByName[it.trim().lowercase()]?.id }
                        if (catIds.isNotEmpty()) {
                            mangaRepository.setMangaCategories(mangaId, catIds)
                        }
                    }

                    // Progreso de capítulos en memoria (actualiza localmente y guarda solo el último si es más reciente)
                    applyMangaChapterProgressInMemory(userId, cloudItem.id, mangaId, cloudItem = cloudItem)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al procesar manga de Supabase: ${cloudItem.id}" }
            }
        }

        // 3. Local -> Nube: Subir favoritos locales de manga
        val updatedFavorites = getMangaFavorites.await()
        val toUploadList = mutableListOf<CloudLibraryItem>()

        updatedFavorites.forEach { localManga ->
            val pkgName = mangaExtensionManager.getExtensionPackage(localManga.source)
            val source = mangaSourceManager.get(localManga.source)
            val sourceName = source?.name ?: "Unknown"
            val baseUrl = (source as? HttpSource)?.baseUrl.orEmpty().trimEnd('/')
            val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val sourceExtId = if (!pkgName.isNullOrBlank()) "ext_$pkgName" else "ext_$sourceSlug"

            val fullUrl = if (localManga.url.startsWith("http://") || localManga.url.startsWith("https://")) {
                localManga.url
            } else if (baseUrl.isNotEmpty()) {
                "$baseUrl${if (localManga.url.startsWith("/")) "" else "/"}${localManga.url}"
            } else {
                localManga.url
            }

            val encodedFullUrl = URLEncoder.encode(fullUrl, "UTF-8")
            val defaultCloudMangaId = "$sourceExtId::$encodedFullUrl"

            val localCleanPath = cleanRelativePath(localManga.url)
            val existingCloudItem = dedupedCloudManga.find { item ->
                val cloudUrl = item.mangaData["url"]?.jsonPrimitive?.content.orEmpty()
                val cloudPath = item.mangaData["path"]?.jsonPrimitive?.content.orEmpty()
                val cloudTitle = item.mangaData["title"]?.jsonPrimitive?.content.orEmpty()
                val cloudSlug = if (item.id.contains("::")) item.id.substringAfter("::") else item.id

                item.id == defaultCloudMangaId ||
                    item.id.endsWith("::$encodedFullUrl") ||
                    cloudUrl == fullUrl ||
                    (localCleanPath.isNotEmpty() && (cleanRelativePath(cloudUrl) == localCleanPath || cleanRelativePath(cloudPath) == localCleanPath)) ||
                    (cloudSlug.isNotEmpty() && localManga.url.contains(cloudSlug)) ||
                    (cloudTitle.isNotBlank() && cloudTitle.equals(localManga.title.trim(), ignoreCase = true))
            }
            val cloudMangaId = existingCloudItem?.id ?: defaultCloudMangaId
            mangaToCloudIdMap[localManga.id] = cloudMangaId

            // Limpiar filas duplicadas en Supabase si existen
            val duplicates = cloudMangaItems.filter { other ->
                other.id != cloudMangaId && (
                    (other.mangaData["title"]?.jsonPrimitive?.content?.trim()?.equals(localManga.title.trim(), ignoreCase = true) == true) ||
                    (localCleanPath.isNotEmpty() && cleanRelativePath(other.mangaData["url"]?.jsonPrimitive?.content.orEmpty()) == localCleanPath)
                )
            }
            duplicates.forEach { dup ->
                client.deleteLibraryItem(dup.id, userId)
            }

            val mangaCategories = getMangaCategories.await(localManga.id).map { it.name }
            val chapters = chapterRepository.getChapterByMangaId(localManga.id)
            val lastReadCh = chapters.filter { it.read }.maxByOrNull { it.chapterNumber }
            val existingCloudNum = existingCloudItem?.lastChapterNumber
                ?: existingCloudItem?.mangaData?.get("lastChapterNumber")?.jsonPrimitive?.content?.toDoubleOrNull()
            val effectiveChNum = lastReadCh?.chapterNumber ?: existingCloudNum
            val effectiveChId = lastReadCh?.let { it.id.toString() } ?: existingCloudItem?.lastChapterRead
            val effectiveChName = lastReadCh?.name ?: existingCloudItem?.lastChapterName

            val mangaDataJson = buildJsonObject {
                put("id", cloudMangaId)
                put("sourceId", sourceExtId)
                put("sourceNumericId", localManga.source.toString())
                put("sourcePkgName", pkgName.orEmpty())
                put("sourceName", sourceName)
                put("sourceLang", (source as? CatalogueSource)?.lang ?: "all")
                put("url", fullUrl)
                put("path", cleanRelativePath(localManga.url))
                put("title", localManga.title)
                put("artist", localManga.artist.orEmpty())
                put("author", localManga.author.orEmpty())
                put("description", localManga.description.orEmpty())
                putJsonArray("genre") {
                    localManga.genre?.forEach { add(JsonPrimitive(it)) }
                }
                put("status", mapStatusLongToString(localManga.status))
                put("inLibrary", true)
                put("thumbnailUrl", localManga.thumbnailUrl.orEmpty())
                putJsonArray("categories") {
                    mangaCategories.forEach { add(JsonPrimitive(it)) }
                }
                put("lastUpdate", localManga.lastUpdate)
                if (effectiveChNum != null) {
                    put("lastChapterNumber", effectiveChNum)
                }
                if (!effectiveChId.isNullOrBlank()) {
                    put("lastChapterRead", effectiveChId)
                }
                if (!effectiveChName.isNullOrBlank()) {
                    put("lastChapterName", effectiveChName)
                }
            }

            toUploadList.add(
                CloudLibraryItem(
                    id = cloudMangaId,
                    userId = userId,
                    mangaData = mangaDataJson,
                    categories = mangaCategories,
                    lastUpdate = localManga.lastUpdate,
                    lastChapterRead = effectiveChId,
                    lastChapterName = effectiveChName,
                ),
            )
        }

        if (toUploadList.isNotEmpty()) {
            client.upsertLibraryItems(toUploadList)
        }

        // Guardar set actualizado de IDs de manga sincronizados
        val allSyncedIds = mutableSetOf<String>()
        cloudMangaItems.forEach { allSyncedIds.add(it.id); it.mangaData["url"]?.jsonPrimitive?.content?.let { u -> allSyncedIds.add(u) } }
        toUploadList.forEach { allSyncedIds.add(it.id); it.mangaData["url"]?.jsonPrimitive?.content?.let { u -> allSyncedIds.add(u) } }
        preferences.syncedCloudIds.set(allSyncedIds.filter { it.isNotBlank() }.toSet())

        return updatedFavorites.size
    }

    // ---------------- ANIME LIBRARY SYNC ----------------

    private suspend fun syncAnimeLibrary(
        userId: String,
        cloudAnimeItems: List<CloudLibraryItem>,
    ): Int {
        // 0. Deduplicar base de datos local de anime
        val rawLocalAnimeFavorites = getAnimeFavorites.await()
        val seenLocalAnimeTitles = mutableSetOf<String>()
        rawLocalAnimeFavorites.forEach { fav ->
            val cleanTitle = fav.title.trim().lowercase()
            if (!seenLocalAnimeTitles.add(cleanTitle)) {
                logcat(LogPriority.INFO) { "Eliminando duplicado local de anime: ${fav.title} (id: ${fav.id})" }
                animeRepository.updateAnime(AnimeUpdate(id = fav.id, favorite = false))
            }
        }

        // 0.5. Deduplicar items de la nube y eliminar IDs redundantes
        val dedupedCloudAnime = mutableListOf<CloudLibraryItem>()
        val seenCloudAnimeKeys = mutableSetOf<String>()
        val cloudAnimeIdsToDelete = mutableListOf<String>()

        cloudAnimeItems.forEach { item ->
            val title = item.mangaData["title"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val path = cleanRelativePath(item.mangaData["path"]?.jsonPrimitive?.content ?: item.mangaData["url"]?.jsonPrimitive?.content.orEmpty()).lowercase()
            val key = if (title.isNotEmpty()) "title_$title" else if (path.isNotEmpty()) "path_$path" else item.id

            if (seenCloudAnimeKeys.add(key)) {
                dedupedCloudAnime.add(item)
            } else {
                cloudAnimeIdsToDelete.add(item.id)
            }
        }
        cloudAnimeIdsToDelete.forEach { dupId ->
            client.deleteLibraryItem(dupId, userId)
        }

        val localFavorites = getAnimeFavorites.await()
        val localFavoritesByUrl = localFavorites.associateBy { it.url }

        val localCategories = getAnimeCategories.await()
        val localCategoriesByName = localCategories.associateBy { it.name.trim().lowercase() }

        val previousSyncedIds = preferences.syncedCloudAnimeIds.get()

        // 1. Verificar eliminaciones de anime en la nube
        localFavorites.forEach { localAnime ->
            val pkgName = animeExtensionManager.getExtensionPackage(localAnime.source)
            val source = animeSourceManager.get(localAnime.source)
            val sourceName = source?.name ?: "Unknown"
            val baseUrl = (source as? AnimeHttpSource)?.baseUrl.orEmpty().trimEnd('/')
            val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val sourceExtId = if (!pkgName.isNullOrBlank()) "ext_$pkgName" else "ext_$sourceSlug"
            val fullUrl = if (localAnime.url.startsWith("http://") || localAnime.url.startsWith("https://")) {
                localAnime.url
            } else if (baseUrl.isNotEmpty()) {
                "$baseUrl${if (localAnime.url.startsWith("/")) "" else "/"}${localAnime.url}"
            } else {
                localAnime.url
            }
            val encodedUrl = URLEncoder.encode(fullUrl, "UTF-8")
            val defaultCloudId = "$sourceExtId::$encodedUrl"

            val isPresentInCloud = dedupedCloudAnime.any { item ->
                item.id == defaultCloudId ||
                    item.id.endsWith("::$encodedUrl") ||
                    item.mangaData["url"]?.jsonPrimitive?.content == fullUrl ||
                    item.mangaData["title"]?.jsonPrimitive?.content?.equals(localAnime.title, ignoreCase = true) == true
            }

            if (!isPresentInCloud) {
                val wasPreviouslySynced = previousSyncedIds.contains(defaultCloudId) ||
                    previousSyncedIds.contains(fullUrl) ||
                    previousSyncedIds.contains(localAnime.url)

                if (wasPreviouslySynced) {
                    logcat(LogPriority.INFO) { "Eliminando favorito de anime local por eliminación en la nube: ${localAnime.title}" }
                    animeRepository.updateAnime(AnimeUpdate(id = localAnime.id, favorite = false, dateAdded = 0L))
                }
            }
        }

        // 2. Nube -> Local: Importar animes de Supabase (deduplicados)
        val importedLocalAnimeTitles = mutableSetOf<String>()
        dedupedCloudAnime.forEach { cloudItem ->
            try {
                val dataObj = cloudItem.mangaData
                val title = dataObj["title"]?.jsonPrimitive?.content ?: "Sin título"
                if (importedLocalAnimeTitles.contains(title.trim().lowercase())) {
                    return@forEach
                }
                importedLocalAnimeTitles.add(title.trim().lowercase())

                val rawUrl = dataObj["url"]?.jsonPrimitive?.content ?: ""
                val pathUrl = dataObj["path"]?.jsonPrimitive?.content
                val sourceIdRaw = dataObj["sourceNumericId"]?.jsonPrimitive?.content
                    ?: dataObj["sourceId"]?.jsonPrimitive?.content ?: ""
                val sourceName = dataObj["sourceName"]?.jsonPrimitive?.content

                val effectiveUrl = if (!pathUrl.isNullOrBlank()) pathUrl else rawUrl
                val (sourceId, url) = resolveAnimeSource(sourceIdRaw, effectiveUrl, sourceName)
                val cleanUrl = cleanRelativePath(url)

                val artist = dataObj["artist"]?.jsonPrimitive?.content
                val author = dataObj["author"]?.jsonPrimitive?.content
                val desc = dataObj["description"]?.jsonPrimitive?.content
                val thumb = dataObj["thumbnailUrl"]?.jsonPrimitive?.content
                val statusStr = dataObj["status"]?.jsonPrimitive?.content ?: "UNKNOWN"
                val statusLong = mapStatusStringToLong(statusStr)

                val genresList = try {
                    dataObj["genre"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (cleanUrl.isNotBlank()) {
                    val existing = getAnimeByUrlAndSourceId.await(cleanUrl, sourceId)
                        ?: localFavoritesByUrl[cleanUrl]
                        ?: localFavoritesByUrl[url]
                        ?: localFavoritesByUrl[rawUrl]
                        ?: localFavorites.firstOrNull { it.title.equals(title, ignoreCase = true) }

                    val animeId = if (existing != null) {
                        animeRepository.updateAnime(
                            AnimeUpdate(
                                id = existing.id,
                                source = sourceId,
                                url = cleanUrl,
                                favorite = true,
                                title = title,
                                artist = artist ?: existing.artist,
                                author = author ?: existing.author,
                                description = desc ?: existing.description,
                                genre = genresList.ifEmpty { existing.genre.orEmpty() },
                                status = statusLong,
                                thumbnailUrl = thumb ?: existing.thumbnailUrl,
                                lastUpdate = cloudItem.lastUpdate.takeIf { it > 0 } ?: existing.lastUpdate,
                            ),
                        )
                        existing.id
                    } else {
                        val newAnime = Anime.create().copy(
                            source = sourceId,
                            url = cleanUrl,
                            artist = artist,
                            author = author,
                            description = desc,
                            genre = genresList,
                            title = title,
                            status = statusLong,
                            thumbnailUrl = thumb,
                            favorite = true,
                            lastUpdate = cloudItem.lastUpdate.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            dateAdded = System.currentTimeMillis(),
                        )
                        animeRepository.insertAnime(newAnime) ?: 0L
                    }

                    if (animeId > 0L) {
                        animeToCloudIdMap[animeId] = cloudItem.id
                    }

                    // Categorías del anime
                    val catNames = cloudItem.categories
                    if (catNames.isNotEmpty() && animeId > 0L) {
                        val catIds = catNames.mapNotNull { localCategoriesByName[it.trim().lowercase()]?.id }
                        if (catIds.isNotEmpty()) {
                            animeRepository.setAnimeCategories(animeId, catIds)
                        }
                    }

                    // Sincronizar episodios vistos en memoria (actualiza localmente y guarda solo el último si es más reciente)
                    applyAnimeEpisodeProgressInMemory(userId, cloudItem.id, animeId, cloudItem = cloudItem)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al procesar anime de Supabase: ${cloudItem.id}" }
            }
        }

        // 3. Local -> Nube: Subir favoritos locales de anime
        val updatedFavorites = getAnimeFavorites.await()
        val toUploadList = mutableListOf<CloudLibraryItem>()

        updatedFavorites.forEach { localAnime ->
            val pkgName = animeExtensionManager.getExtensionPackage(localAnime.source)
            val source = animeSourceManager.get(localAnime.source)
            val sourceName = source?.name ?: "Unknown"
            val baseUrl = (source as? AnimeHttpSource)?.baseUrl.orEmpty().trimEnd('/')
            val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val sourceExtId = if (!pkgName.isNullOrBlank()) "ext_$pkgName" else "ext_$sourceSlug"

            val fullUrl = if (localAnime.url.startsWith("http://") || localAnime.url.startsWith("https://")) {
                localAnime.url
            } else if (baseUrl.isNotEmpty()) {
                "$baseUrl${if (localAnime.url.startsWith("/")) "" else "/"}${localAnime.url}"
            } else {
                localAnime.url
            }

            val encodedFullUrl = URLEncoder.encode(fullUrl, "UTF-8")
            val defaultCloudAnimeId = "$sourceExtId::$encodedFullUrl"

            val localCleanPath = cleanRelativePath(localAnime.url)
            val existingCloudItem = dedupedCloudAnime.find { item ->
                val cloudUrl = item.mangaData["url"]?.jsonPrimitive?.content.orEmpty()
                val cloudPath = item.mangaData["path"]?.jsonPrimitive?.content.orEmpty()
                val cloudTitle = item.mangaData["title"]?.jsonPrimitive?.content.orEmpty()
                val cloudSlug = if (item.id.contains("::")) item.id.substringAfter("::") else item.id

                item.id == defaultCloudAnimeId ||
                    item.id.endsWith("::$encodedFullUrl") ||
                    cloudUrl == fullUrl ||
                    (localCleanPath.isNotEmpty() && (cleanRelativePath(cloudUrl) == localCleanPath || cleanRelativePath(cloudPath) == localCleanPath)) ||
                    (cloudSlug.isNotEmpty() && localAnime.url.contains(cloudSlug)) ||
                    (cloudTitle.isNotBlank() && cloudTitle.equals(localAnime.title.trim(), ignoreCase = true))
            }
            val cloudAnimeId = existingCloudItem?.id ?: defaultCloudAnimeId
            animeToCloudIdMap[localAnime.id] = cloudAnimeId

            // Limpiar filas duplicadas en Supabase si existen
            val duplicates = cloudAnimeItems.filter { other ->
                other.id != cloudAnimeId && (
                    (other.mangaData["title"]?.jsonPrimitive?.content?.trim()?.equals(localAnime.title.trim(), ignoreCase = true) == true) ||
                    (localCleanPath.isNotEmpty() && cleanRelativePath(other.mangaData["url"]?.jsonPrimitive?.content.orEmpty()) == localCleanPath)
                )
            }
            duplicates.forEach { dup ->
                client.deleteLibraryItem(dup.id, userId)
            }

            val animeCategories = getAnimeCategories.await(localAnime.id).map { it.name }
            val episodes = episodeRepository.getEpisodeByAnimeId(localAnime.id)
            val lastSeenEp = episodes.filter { it.seen }.maxByOrNull { it.episodeNumber }
            val existingCloudNum = existingCloudItem?.lastChapterNumber
                ?: existingCloudItem?.mangaData?.get("lastChapterNumber")?.jsonPrimitive?.content?.toDoubleOrNull()
            val effectiveEpNum = lastSeenEp?.episodeNumber ?: existingCloudNum
            val effectiveEpId = lastSeenEp?.let { it.id.toString() } ?: existingCloudItem?.lastChapterRead
            val effectiveEpName = lastSeenEp?.name ?: existingCloudItem?.lastChapterName

            val animeDataJson = buildJsonObject {
                put("id", cloudAnimeId)
                put("mediaType", "anime")
                put("isAnime", true)
                put("sourceId", sourceExtId)
                put("sourceNumericId", localAnime.source.toString())
                put("sourcePkgName", pkgName.orEmpty())
                put("sourceName", sourceName)
                put("sourceLang", (source as? AnimeCatalogueSource)?.lang ?: "all")
                put("url", fullUrl)
                put("path", cleanRelativePath(localAnime.url))
                put("title", localAnime.title)
                put("artist", localAnime.artist.orEmpty())
                put("author", localAnime.author.orEmpty())
                put("description", localAnime.description.orEmpty())
                putJsonArray("genre") {
                    localAnime.genre?.forEach { add(JsonPrimitive(it)) }
                }
                put("status", mapStatusLongToString(localAnime.status))
                put("inLibrary", true)
                put("thumbnailUrl", localAnime.thumbnailUrl.orEmpty())
                putJsonArray("categories") {
                    animeCategories.forEach { add(JsonPrimitive(it)) }
                }
                put("lastUpdate", localAnime.lastUpdate)
                if (episodes.isNotEmpty()) {
                    put("totalEpisodes", episodes.size)
                }
                if (effectiveEpNum != null) {
                    put("lastChapterNumber", effectiveEpNum)
                }
                if (!effectiveEpId.isNullOrBlank()) {
                    put("lastChapterRead", effectiveEpId)
                }
                if (!effectiveEpName.isNullOrBlank()) {
                    put("lastChapterName", effectiveEpName)
                }
            }

            toUploadList.add(
                CloudLibraryItem(
                    id = cloudAnimeId,
                    userId = userId,
                    mangaData = animeDataJson,
                    categories = animeCategories,
                    lastUpdate = localAnime.lastUpdate,
                    lastChapterRead = effectiveEpId,
                    lastChapterName = effectiveEpName,
                ),
            )
        }

        if (toUploadList.isNotEmpty()) {
            client.upsertLibraryItems(toUploadList)
        }

        // Guardar set actualizado de IDs de anime sincronizados
        val allSyncedIds = mutableSetOf<String>()
        cloudAnimeItems.forEach { allSyncedIds.add(it.id); it.mangaData["url"]?.jsonPrimitive?.content?.let { u -> allSyncedIds.add(u) } }
        toUploadList.forEach { allSyncedIds.add(it.id); it.mangaData["url"]?.jsonPrimitive?.content?.let { u -> allSyncedIds.add(u) } }
        preferences.syncedCloudAnimeIds.set(allSyncedIds.filter { it.isNotBlank() }.toSet())

        return updatedFavorites.size
    }

    // ---------------- IN-MEMORY CHAPTER / EPISODE MATCHING ----------------

    private fun extractNumber(str: String?): Double? {
        if (str.isNullOrBlank()) return null
        try {
            val tagMatch = Regex("""::(?:ep|ch)::(\d+(?:\.\d+)?)""").find(str)
            if (tagMatch != null) return tagMatch.groupValues[1].toDoubleOrNull()

            val wordMatch = Regex("""(?:cap[ií]tulo|episodio|ep|ch|cap|#)\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(str)
            if (wordMatch != null) return wordMatch.groupValues[1].toDoubleOrNull()

            val urlMatch = Regex("""(?:-|/|_|\b)(\d+(?:\.\d+)?)(?:/|\?|$)""").find(str)
            if (urlMatch != null) return urlMatch.groupValues[1].toDoubleOrNull()

            val anyNum = Regex("""\b(\d+(?:\.\d+)?)\b""").find(str)
            return anyNum?.groupValues?.get(1)?.toDoubleOrNull()
        } catch (_: Exception) {
            return null
        }
    }

    private fun filterProgressForEntry(
        entryId: String,
        title: String,
        rawUrl: String,
        allProgress: List<CloudChapterProgress>,
    ): List<CloudChapterProgress> {
        val cleanSlug = entryId.substringAfter("::").removePrefix("ext_").lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanUrl = cleanRelativePath(rawUrl).lowercase()

        return allProgress.filter { p ->
            val pMangaId = p.mangaId.lowercase()
            p.mangaId == entryId ||
                (cleanSlug.isNotEmpty() && pMangaId.contains(cleanSlug)) ||
                (cleanUrl.isNotEmpty() && pMangaId.contains(cleanUrl)) ||
                (cleanTitle.isNotEmpty() && pMangaId.replace(Regex("[^a-z0-9]"), "").contains(cleanTitle))
        }
    }

    private suspend fun applyAnimeEpisodeProgressInMemory(
        userId: String,
        cloudAnimeId: String,
        localAnimeId: Long,
        cloudProgressList: List<CloudChapterProgress> = emptyList(),
        cloudItem: CloudLibraryItem? = null,
    ) {
        val localEpisodes = episodeRepository.getEpisodeByAnimeId(localAnimeId)
        if (localEpisodes.isEmpty()) return

        val lastNumFromData = cloudItem?.lastChapterNumber
            ?: cloudItem?.mangaData?.get("lastChapterNumber")?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: extractNumber(cloudItem?.lastChapterName)
            ?: extractNumber(cloudItem?.lastChapterRead)
            ?: extractNumber(cloudItem?.mangaData?.get("last_chapter_name")?.jsonPrimitive?.content)
            ?: extractNumber(cloudItem?.mangaData?.get("lastChapterName")?.jsonPrimitive?.content)
            ?: extractNumber(cloudItem?.mangaData?.get("last_chapter_read")?.jsonPrimitive?.content)

        val initialMaxLocalSeen = localEpisodes.filter { it.seen }.maxByOrNull { it.episodeNumber }
        val localIsAhead = initialMaxLocalSeen != null && (lastNumFromData == null || initialMaxLocalSeen.episodeNumber > lastNumFromData)

        localEpisodes.forEach { ep ->
            val isSeenInCloud = cloudProgressList.any { cp ->
                if (!cp.read) return@any false
                if (cp.chapterId == ep.id.toString() || cp.chapterId == ep.url) return@any true
                val cleanEpUrl = cleanRelativePath(ep.url)
                val cleanCpId = cleanRelativePath(cp.chapterId)
                if (cleanEpUrl.isNotEmpty() && (cleanCpId == cleanEpUrl || cleanCpId.contains(cleanEpUrl) || cleanEpUrl.contains(cleanCpId))) return@any true
                val cpName = cp.chapterName?.trim().orEmpty()
                if (cpName.isNotEmpty() && (cpName.equals(ep.name.trim(), ignoreCase = true) || ep.name.contains(cpName, ignoreCase = true))) return@any true
                val cpNum = extractNumber(cp.chapterId) ?: extractNumber(cp.chapterName)
                if (cpNum != null && ep.episodeNumber > 0.0 && (cpNum == ep.episodeNumber || Math.abs(cpNum - ep.episodeNumber) < 0.05)) return@any true
                false
            }

            val epNum = if (ep.episodeNumber > 0.0) ep.episodeNumber else (extractNumber(ep.name) ?: extractNumber(ep.url) ?: 0.0)
            if (lastNumFromData != null && epNum > 0.0) {
                val shouldBeSeen = epNum <= lastNumFromData
                if (shouldBeSeen && !ep.seen) {
                    episodeRepository.updateEpisode(EpisodeUpdate(id = ep.id, seen = true))
                } else if (!shouldBeSeen && ep.seen && !localIsAhead) {
                    episodeRepository.updateEpisode(EpisodeUpdate(id = ep.id, seen = false))
                }
            } else if (isSeenInCloud && !ep.seen) {
                episodeRepository.updateEpisode(EpisodeUpdate(id = ep.id, seen = true))
            }
        }

        // Si localmente hay episodios vistos más avanzados que en la nube, actualizar solo el último en user_library
        val maxLocalSeen = initialMaxLocalSeen ?: localEpisodes.filter { it.seen }.maxByOrNull { it.episodeNumber }
        if (maxLocalSeen != null && (lastNumFromData == null || maxLocalSeen.episodeNumber > lastNumFromData)) {
            val localAnime = animeRepository.getAnimeById(localAnimeId)
            client.updateLastRead(
                userId = userId,
                mangaOrAnimeId = cloudAnimeId,
                chapterOrEpId = maxLocalSeen.id.toString(),
                chapterOrEpName = maxLocalSeen.name,
                chapterNumber = maxLocalSeen.episodeNumber,
                itemTitle = localAnime?.title,
            )
        }
    }

    private suspend fun applyMangaChapterProgressInMemory(
        userId: String,
        cloudMangaId: String,
        localMangaId: Long,
        cloudProgressList: List<CloudChapterProgress> = emptyList(),
        cloudItem: CloudLibraryItem? = null,
    ) {
        val localChapters = chapterRepository.getChapterByMangaId(localMangaId)
        if (localChapters.isEmpty()) return

        val lastNumFromData = cloudItem?.lastChapterNumber
            ?: cloudItem?.mangaData?.get("lastChapterNumber")?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: extractNumber(cloudItem?.lastChapterName)
            ?: extractNumber(cloudItem?.lastChapterRead)
            ?: extractNumber(cloudItem?.mangaData?.get("last_chapter_name")?.jsonPrimitive?.content)
            ?: extractNumber(cloudItem?.mangaData?.get("lastChapterName")?.jsonPrimitive?.content)
            ?: extractNumber(cloudItem?.mangaData?.get("last_chapter_read")?.jsonPrimitive?.content)

        val initialMaxLocalRead = localChapters.filter { it.read }.maxByOrNull { it.chapterNumber }
        val localIsAhead = initialMaxLocalRead != null && (lastNumFromData == null || initialMaxLocalRead.chapterNumber > lastNumFromData)

        localChapters.forEach { ch ->
            val isReadInCloud = cloudProgressList.any { cp ->
                if (!cp.read) return@any false
                if (cp.chapterId == ch.id.toString() || cp.chapterId == ch.url) return@any true
                val cleanChUrl = cleanRelativePath(ch.url)
                val cleanCpId = cleanRelativePath(cp.chapterId)
                if (cleanChUrl.isNotEmpty() && (cleanCpId == cleanChUrl || cleanCpId.contains(cleanChUrl) || cleanChUrl.contains(cleanCpId))) return@any true
                val cpName = cp.chapterName?.trim().orEmpty()
                if (cpName.isNotEmpty() && (cpName.equals(ch.name.trim(), ignoreCase = true) || ch.name.contains(cpName, ignoreCase = true))) return@any true
                val cpNum = extractNumber(cp.chapterId) ?: extractNumber(cp.chapterName)
                if (cpNum != null && ch.chapterNumber > 0.0 && (cpNum == ch.chapterNumber || Math.abs(cpNum - ch.chapterNumber) < 0.05)) return@any true
                false
            }

            val chNum = if (ch.chapterNumber > 0.0) ch.chapterNumber else (extractNumber(ch.name) ?: extractNumber(ch.url) ?: 0.0)
            if (lastNumFromData != null && chNum > 0.0) {
                val shouldBeRead = chNum <= lastNumFromData
                if (shouldBeRead && !ch.read) {
                    chapterRepository.updateChapter(ChapterUpdate(id = ch.id, read = true))
                } else if (!shouldBeRead && ch.read && !localIsAhead) {
                    chapterRepository.updateChapter(ChapterUpdate(id = ch.id, read = false))
                }
            } else if (isReadInCloud && !ch.read) {
                chapterRepository.updateChapter(ChapterUpdate(id = ch.id, read = true))
            }
        }

        // Si localmente hay capítulos leídos más avanzados que en la nube, actualizar solo el último en user_library
        val maxLocalRead = initialMaxLocalRead ?: localChapters.filter { it.read }.maxByOrNull { it.chapterNumber }
        if (maxLocalRead != null && (lastNumFromData == null || maxLocalRead.chapterNumber > lastNumFromData)) {
            val localManga = mangaRepository.getMangaById(localMangaId)
            client.updateLastRead(
                userId = userId,
                mangaOrAnimeId = cloudMangaId,
                chapterOrEpId = maxLocalRead.id.toString(),
                chapterOrEpName = maxLocalRead.name,
                chapterNumber = maxLocalRead.chapterNumber,
                itemTitle = localManga?.title,
            )
        }
    }

    // ---------------- DELETIONS & REALTIME PROGRESS SYNC ----------------

    fun getOrComputeCloudMangaId(manga: Manga): String {
        val cached = mangaToCloudIdMap[manga.id]
        if (cached != null) return cached
        val pkgName = mangaExtensionManager.getExtensionPackage(manga.source)
        val source = mangaSourceManager.get(manga.source)
        val sourceName = source?.name ?: "Unknown"
        val baseUrl = (source as? HttpSource)?.baseUrl.orEmpty().trimEnd('/')
        val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val sourceExtId = if (!pkgName.isNullOrBlank()) "ext_$pkgName" else "ext_$sourceSlug"
        val fullUrl = if (manga.url.startsWith("http://") || manga.url.startsWith("https://")) {
            manga.url
        } else if (baseUrl.isNotEmpty()) {
            "$baseUrl${if (manga.url.startsWith("/")) "" else "/"}${manga.url}"
        } else {
            manga.url
        }
        val encodedUrl = URLEncoder.encode(fullUrl, "UTF-8")
        val id = "$sourceExtId::$encodedUrl"
        mangaToCloudIdMap[manga.id] = id
        return id
    }

    fun getOrComputeCloudAnimeId(anime: Anime): String {
        val cached = animeToCloudIdMap[anime.id]
        if (cached != null) return cached
        val pkgName = animeExtensionManager.getExtensionPackage(anime.source)
        val source = animeSourceManager.get(anime.source)
        val sourceName = source?.name ?: "Unknown"
        val baseUrl = (source as? AnimeHttpSource)?.baseUrl.orEmpty().trimEnd('/')
        val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val sourceExtId = if (!pkgName.isNullOrBlank()) "ext_$pkgName" else "ext_$sourceSlug"
        val fullUrl = if (anime.url.startsWith("http://") || anime.url.startsWith("https://")) {
            anime.url
        } else if (baseUrl.isNotEmpty()) {
            "$baseUrl${if (anime.url.startsWith("/")) "" else "/"}${anime.url}"
        } else {
            anime.url
        }
        val encodedUrl = URLEncoder.encode(fullUrl, "UTF-8")
        val id = "$sourceExtId::$encodedUrl"
        animeToCloudIdMap[anime.id] = id
        return id
    }

    fun syncChapterProgress(mangaId: Long, chapterId: Long, chapterName: String, read: Boolean = true) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val manga = mangaRepository.getMangaById(mangaId) ?: return@launch
                val cloudMangaId = getOrComputeCloudMangaId(manga)
                if (read) {
                    val chapter = chapterRepository.getChapterById(chapterId)
                    val chNum = chapter?.chapterNumber ?: extractNumber(chapterName)
                    client.updateLastRead(
                        userId = userId,
                        mangaOrAnimeId = cloudMangaId,
                        chapterOrEpId = chapterId.toString(),
                        chapterOrEpName = chapterName,
                        chapterNumber = chNum,
                        itemTitle = manga.title,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al sincronizar lectura de capítulo $chapterId de manga $mangaId" }
            }
        }
    }

    fun syncEpisodeProgress(animeId: Long, episodeId: Long, episodeName: String, seen: Boolean = true) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val anime = animeRepository.getAnimeById(animeId) ?: return@launch
                val cloudAnimeId = getOrComputeCloudAnimeId(anime)
                if (seen) {
                    val episode = episodeRepository.getEpisodeById(episodeId)
                    val epNum = episode?.episodeNumber ?: extractNumber(episodeName)
                    client.updateLastRead(
                        userId = userId,
                        mangaOrAnimeId = cloudAnimeId,
                        chapterOrEpId = episodeId.toString(),
                        chapterOrEpName = episodeName,
                        chapterNumber = epNum,
                        itemTitle = anime.title,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al sincronizar reproducción de episodio $episodeId de anime $animeId" }
            }
        }
    }

    fun syncChaptersProgress(mangaId: Long, chapters: Array<out tachiyomi.domain.items.chapter.model.Chapter>, read: Boolean) {
        if (!preferences.isLoggedIn() || chapters.isEmpty()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val manga = mangaRepository.getMangaById(mangaId) ?: return@launch
                val cloudMangaId = getOrComputeCloudMangaId(manga)
                if (read) {
                    val lastCh = chapters.maxByOrNull { it.chapterNumber } ?: chapters.last()
                    client.updateLastRead(
                        userId = userId,
                        mangaOrAnimeId = cloudMangaId,
                        chapterOrEpId = lastCh.id.toString(),
                        chapterOrEpName = lastCh.name,
                        chapterNumber = lastCh.chapterNumber,
                        itemTitle = manga.title,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al sincronizar capítulos de manga $mangaId" }
            }
        }
    }

    fun syncEpisodesProgress(animeId: Long, episodes: Array<out tachiyomi.domain.items.episode.model.Episode>, seen: Boolean) {
        if (!preferences.isLoggedIn() || episodes.isEmpty()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val anime = animeRepository.getAnimeById(animeId) ?: return@launch
                val cloudAnimeId = getOrComputeCloudAnimeId(anime)
                if (seen) {
                    val lastEp = episodes.maxByOrNull { it.episodeNumber } ?: episodes.last()
                    client.updateLastRead(
                        userId = userId,
                        mangaOrAnimeId = cloudAnimeId,
                        chapterOrEpId = lastEp.id.toString(),
                        chapterOrEpName = lastEp.name,
                        chapterNumber = lastEp.episodeNumber,
                        itemTitle = anime.title,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al sincronizar episodios de anime $animeId" }
            }
        }
    }

    fun deleteMangaFromCloud(mangaId: Long) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val manga = mangaRepository.getMangaById(mangaId)
                val title = manga?.title.orEmpty()
                val url = manga?.url.orEmpty()
                val cloudMangaId = manga?.let { getOrComputeCloudMangaId(it) }.orEmpty()

                if (cloudMangaId.isNotBlank()) {
                    client.deleteLibraryItem(cloudMangaId, userId)
                }
                if (title.isNotBlank() || url.isNotBlank()) {
                    client.deleteLibraryItemByTitleOrUrl(title, url, userId)
                }
                mangaToCloudIdMap.remove(mangaId)

                val currentSynced = preferences.syncedCloudIds.get().toMutableSet()
                if (cloudMangaId.isNotBlank()) currentSynced.remove(cloudMangaId)
                if (url.isNotBlank()) currentSynced.remove(url)
                preferences.syncedCloudIds.set(currentSynced)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error al eliminar manga de Supabase: $mangaId" }
            }
        }
    }

    fun deleteAnimeFromCloud(animeId: Long) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val anime = animeRepository.getAnimeById(animeId)
                val title = anime?.title.orEmpty()
                val url = anime?.url.orEmpty()
                val cloudAnimeId = anime?.let { getOrComputeCloudAnimeId(it) }.orEmpty()

                if (cloudAnimeId.isNotBlank()) {
                    client.deleteLibraryItem(cloudAnimeId, userId)
                }
                if (title.isNotBlank() || url.isNotBlank()) {
                    client.deleteLibraryItemByTitleOrUrl(title, url, userId)
                }
                animeToCloudIdMap.remove(animeId)

                val currentSynced = preferences.syncedCloudAnimeIds.get().toMutableSet()
                if (cloudAnimeId.isNotBlank()) currentSynced.remove(cloudAnimeId)
                if (url.isNotBlank()) currentSynced.remove(url)
                preferences.syncedCloudAnimeIds.set(currentSynced)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error al eliminar anime de Supabase: $animeId" }
            }
        }
    }

    fun updateLastReadForManga(mangaId: Long, chapterId: String, chapterName: String, chapterNumber: Double) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val manga = mangaRepository.getMangaById(mangaId) ?: return@launch
                val cloudMangaId = getOrComputeCloudMangaId(manga)
                client.updateLastRead(
                    userId = userId,
                    mangaOrAnimeId = cloudMangaId,
                    chapterOrEpId = chapterId,
                    chapterOrEpName = chapterName,
                    chapterNumber = chapterNumber,
                    itemTitle = manga.title,
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al actualizar último leído de manga $mangaId" }
            }
        }
    }

    fun updateLastReadForAnime(animeId: Long, episodeId: String, episodeName: String, episodeNumber: Double) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                client.ensureValidToken()
                val anime = animeRepository.getAnimeById(animeId) ?: return@launch
                val cloudAnimeId = getOrComputeCloudAnimeId(anime)
                client.updateLastRead(
                    userId = userId,
                    mangaOrAnimeId = cloudAnimeId,
                    chapterOrEpId = episodeId,
                    chapterOrEpName = episodeName,
                    chapterNumber = episodeNumber,
                    itemTitle = anime.title,
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al actualizar último visto de anime $animeId" }
            }
        }
    }

    fun applyCloudProgressForManga(mangaId: Long) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                val manga = mangaRepository.getMangaById(mangaId) ?: return@launch
                val localChapters = chapterRepository.getChapterByMangaId(mangaId)
                if (localChapters.isEmpty()) return@launch

                val cloudMangaId = getOrComputeCloudMangaId(manga)
                val cloudItems = client.fetchLibrary(userId).getOrElse { emptyList() }
                val cleanLocalPath = cleanRelativePath(manga.url)
                val cloudItem = cloudItems.find { item ->
                    item.id == cloudMangaId ||
                        (cleanLocalPath.isNotEmpty() && cleanRelativePath(item.mangaData["url"]?.jsonPrimitive?.content.orEmpty()) == cleanLocalPath) ||
                        item.mangaData["title"]?.jsonPrimitive?.content?.trim().equals(manga.title.trim(), ignoreCase = true)
                }

                applyMangaChapterProgressInMemory(userId, cloudItem?.id ?: cloudMangaId, mangaId, cloudItem = cloudItem)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al aplicar progreso cloud para manga $mangaId" }
            }
        }
    }

    fun applyCloudProgressForAnime(animeId: Long) {
        if (!preferences.isLoggedIn()) return
        scope.launch {
            val userId = preferences.userId.get().trim()
            if (userId.isBlank()) return@launch
            try {
                val anime = animeRepository.getAnimeById(animeId) ?: return@launch
                val localEpisodes = episodeRepository.getEpisodeByAnimeId(animeId)
                if (localEpisodes.isEmpty()) return@launch

                val cloudAnimeId = getOrComputeCloudAnimeId(anime)
                val cloudItems = client.fetchLibrary(userId).getOrElse { emptyList() }
                val cleanLocalPath = cleanRelativePath(anime.url)
                val cloudItem = cloudItems.find { item ->
                    item.id == cloudAnimeId ||
                        (cleanLocalPath.isNotEmpty() && cleanRelativePath(item.mangaData["url"]?.jsonPrimitive?.content.orEmpty()) == cleanLocalPath) ||
                        item.mangaData["title"]?.jsonPrimitive?.content?.trim().equals(anime.title.trim(), ignoreCase = true)
                }

                applyAnimeEpisodeProgressInMemory(userId, cloudItem?.id ?: cloudAnimeId, animeId, cloudItem = cloudItem)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Error al aplicar progreso cloud para anime $animeId" }
            }
        }
    }

    // ---------------- RESOLVERS & HELPERS ----------------

    private fun cleanRelativePath(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val uri = URI(trimmed)
                val path = uri.rawPath.orEmpty()
                val query = uri.rawQuery
                val combined = if (!query.isNullOrEmpty()) "$path?$query" else path
                if (combined.isEmpty()) "/" else combined
            } else {
                if (!trimmed.startsWith("/")) "/$trimmed" else trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private suspend fun autoRepairLocalSources() {
        try {
            val localAnimes = getAnimeFavorites.await()
            val installedAnimeSources = animeSourceManager.getAll().filterNot { it is StubAnimeSource }
            localAnimes.forEach { anime ->
                val currentSrc = animeSourceManager.get(anime.source)
                if (currentSrc == null || currentSrc is StubAnimeSource) {
                    val (repairedId, repairedUrl) = resolveAnimeSource(
                        sourceIdRaw = anime.source.toString(),
                        rawUrl = anime.url,
                        sourceName = null,
                        title = anime.title,
                    )
                    if (repairedId != anime.source && animeSourceManager.get(repairedId) !is StubAnimeSource) {
                        logcat(LogPriority.INFO) { "Auto-reparando fuente de anime: '${anime.title}' de ${anime.source} -> $repairedId" }
                        animeRepository.updateAnime(
                            AnimeUpdate(
                                id = anime.id,
                                source = repairedId,
                                url = repairedUrl,
                            ),
                        )
                    }
                }
            }

            val localMangas = getMangaFavorites.await()
            val installedMangaSources = mangaSourceManager.getCatalogueSources()
            localMangas.forEach { manga ->
                val currentSrc = mangaSourceManager.get(manga.source)
                if (currentSrc == null || currentSrc is StubMangaSource) {
                    val (repairedId, repairedUrl) = resolveMangaSource(
                        sourceIdRaw = manga.source.toString(),
                        rawUrl = manga.url,
                        sourceName = null,
                        title = manga.title,
                    )
                    if (repairedId != manga.source && mangaSourceManager.get(repairedId) !is StubMangaSource) {
                        logcat(LogPriority.INFO) { "Auto-reparando fuente de manga: '${manga.title}' de ${manga.source} -> $repairedId" }
                        mangaRepository.updateManga(
                            MangaUpdate(
                                id = manga.id,
                                source = repairedId,
                                url = repairedUrl,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Error al ejecutar auto-reparación de fuentes locales" }
        }
    }

    private fun resolveMangaSource(
        sourceIdRaw: String,
        rawUrl: String,
        sourceName: String?,
        title: String? = null,
    ): Pair<Long, String> {
        val cleanUrl = cleanRelativePath(rawUrl)
        val installedSources = mangaSourceManager.getCatalogueSources()
        val numericId = sourceIdRaw.toLongOrNull()

        // 1. Coincidencia directa por ID numérico en fuentes instaladas
        if (numericId != null) {
            val found = installedSources.find { it.id == numericId } ?: mangaSourceManager.get(numericId)
            if (found != null && found !is StubMangaSource) {
                return Pair(found.id, cleanUrl)
            }
        }

        // 2. Extraer paquete y slug
        val rawLower = sourceIdRaw.lowercase()
        val pkgClean = rawLower.removePrefix("ext_")
        val cleanSlug = (sourceName ?: sourceIdRaw).removePrefix("ext_").lowercase().replace(Regex("[^a-z0-9]"), "")
        val urlLower = rawUrl.lowercase()
        val titleLower = title.orEmpty().lowercase()

        // 3. Coincidencia contra fuentes instaladas (por paquete, dominio, nombre o título)
        val matchedSource = installedSources.firstOrNull { src ->
            val srcPkg = mangaExtensionManager.getExtensionPackage(src.id)?.lowercase().orEmpty()
            val srcName = src.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            val srcBaseUrl = (src as? HttpSource)?.baseUrl.orEmpty().lowercase()
            val srcDomain = try { URI(srcBaseUrl).host.orEmpty().lowercase() } catch (_: Exception) { "" }

            (srcPkg.isNotBlank() && (srcPkg == pkgClean || srcPkg.endsWith(".$pkgClean") || srcPkg.contains(pkgClean))) ||
                (srcDomain.isNotBlank() && (urlLower.contains(srcDomain) || srcBaseUrl.contains(cleanSlug))) ||
                (srcName.isNotBlank() && cleanSlug.isNotBlank() && (srcName == cleanSlug || srcName.contains(cleanSlug) || cleanSlug.contains(srcName)))
        }

        if (matchedSource != null) {
            return Pair(matchedSource.id, cleanUrl)
        }

        // 4. Mapeo a fuentes canónicas de manga
        val canonicalMatch = when {
            rawLower.contains("tumangaonline") || rawLower.contains("lectortmo") || rawLower.contains("tmo") ||
                urlLower.contains("tumangaonline") || urlLower.contains("lectortmo") || cleanSlug.contains("tumangaonline") || cleanSlug.contains("tmo") -> {
                installedSources.find { it.name.contains("TuMangaOnline", ignoreCase = true) || it.name.contains("TMO", ignoreCase = true) }?.id ?: 6848293701948301931L
            }
            rawLower.contains("mangadex") || urlLower.contains("mangadex") || cleanSlug.contains("mangadex") -> {
                installedSources.find { it.name.contains("MangaDex", ignoreCase = true) }?.id ?: 2499283573021220255L
            }
            rawLower.contains("inmanga") || urlLower.contains("inmanga") || cleanSlug.contains("inmanga") -> {
                installedSources.find { it.name.contains("InManga", ignoreCase = true) }?.id ?: 5526553258525790403L
            }
            rawLower.contains("mangasin") || rawLower.contains("mangas.in") || urlLower.contains("mangas.in") || urlLower.contains("m440") || cleanSlug.contains("mangasin") -> {
                installedSources.find { it.name.contains("mangas", ignoreCase = true) }?.id ?: 3344386250058668105L
            }
            rawLower.contains("olympus") || urlLower.contains("olympus") || cleanSlug.contains("olympus") -> {
                installedSources.find { it.name.contains("olympus", ignoreCase = true) }?.id ?: 8330759089932087541L
            }
            rawLower.contains("shadowmanga") || rawLower.contains("shademanga") || urlLower.contains("shademanga") || cleanSlug.contains("shadow") -> {
                installedSources.find { it.name.contains("shadow", ignoreCase = true) }?.id ?: 5649269152264667286L
            }
            else -> null
        }

        if (canonicalMatch != null) {
            return Pair(canonicalMatch, cleanUrl)
        }

        val fallbackId = numericId ?: (cleanSlug.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
        return Pair(fallbackId, cleanUrl)
    }

    private fun resolveAnimeSource(
        sourceIdRaw: String,
        rawUrl: String,
        sourceName: String?,
        title: String? = null,
    ): Pair<Long, String> {
        val cleanUrl = cleanRelativePath(rawUrl)
        val allSources = animeSourceManager.getAll()
        val installedSources = allSources.filterNot { it is StubAnimeSource }
        val numericId = sourceIdRaw.toLongOrNull()

        // 1. Coincidencia directa por ID numérico en fuentes instaladas
        if (numericId != null) {
            val found = installedSources.find { it.id == numericId } ?: animeSourceManager.get(numericId)
            if (found != null && found !is StubAnimeSource) {
                return Pair(found.id, normalizeAnimeUrl(found.id, cleanUrl))
            }
        }

        // 2. Extraer paquete y slug
        val rawLower = sourceIdRaw.lowercase()
        val pkgClean = rawLower.removePrefix("ext_")
        val cleanSlug = (sourceName ?: sourceIdRaw).removePrefix("ext_").lowercase().replace(Regex("[^a-z0-9]"), "")
        val urlLower = rawUrl.lowercase()

        // 3. Coincidencia contra fuentes instaladas (por paquete, dominio, o nombre)
        val matchedSource = installedSources.firstOrNull { src ->
            val srcPkg = animeExtensionManager.getExtensionPackage(src.id)?.lowercase().orEmpty()
            val srcName = src.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            val srcBaseUrl = (src as? AnimeHttpSource)?.baseUrl.orEmpty().lowercase()
            val srcDomain = try { URI(srcBaseUrl).host.orEmpty().lowercase() } catch (_: Exception) { "" }

            (srcPkg.isNotBlank() && (srcPkg == pkgClean || srcPkg.endsWith(".$pkgClean") || srcPkg.contains(pkgClean))) ||
                (srcDomain.isNotBlank() && (urlLower.contains(srcDomain) || srcBaseUrl.contains(cleanSlug))) ||
                (srcName.isNotBlank() && cleanSlug.isNotBlank() && (srcName == cleanSlug || srcName.contains(cleanSlug) || cleanSlug.contains(srcName)))
        }

        if (matchedSource != null) {
            return Pair(matchedSource.id, normalizeAnimeUrl(matchedSource.id, cleanUrl))
        }

        // 4. Mapeo a fuentes canónicas de anime
        val canonicalMatch = when {
            rawLower.contains("animeflv") || urlLower.contains("animeflv") || cleanSlug.contains("animeflv") -> 93702975723668971L
            rawLower.contains("jkanime") || urlLower.contains("jkanime") || cleanSlug.contains("jkanime") -> 6007374093317538838L
            rawLower.contains("animeav1") || urlLower.contains("animeav1") || cleanSlug.contains("animeav1") -> 1563723619853520901L
            rawLower.contains("mundodonghua") || urlLower.contains("mundodonghua") || cleanSlug.contains("mundodonghua") -> 4356029013369426954L
            rawLower.contains("animelatinohd") || urlLower.contains("animelatinohd") || cleanSlug.contains("animelatinohd") -> 8725916053360481358L
            rawLower.contains("ninja") || urlLower.contains("ninja") || cleanSlug.contains("ninja") -> 6519391038482819584L
            else -> null
        }

        if (canonicalMatch != null) {
            val installedCanonical = installedSources.find { it.id == canonicalMatch }
            val chosenId = installedCanonical?.id ?: canonicalMatch
            return Pair(chosenId, normalizeAnimeUrl(chosenId, cleanUrl))
        }

        val fallbackId = numericId ?: (cleanSlug.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
        return Pair(fallbackId, normalizeAnimeUrl(fallbackId, cleanUrl))
    }

    private fun normalizeAnimeUrl(sourceId: Long, url: String): String {
        val clean = cleanRelativePath(url)
        return when (sourceId) {
            // AnimeFLV siempre requiere prefijo /anime/<slug>
            93702975723668971L -> {
                if (clean.startsWith("/anime/")) clean else "/anime/${clean.trimStart('/')}"
            }
            // JKanime requiere formato /<slug>/ con barras en ambos extremos
            6007374093317538838L -> {
                val s = clean.trim('/')
                if (s.isEmpty()) "/" else "/$s/"
            }
            else -> clean
        }
    }

    private fun mapStatusStringToLong(status: String): Long {
        return when (status.uppercase()) {
            "ONGOING" -> 1L
            "COMPLETED" -> 2L
            "LICENSED" -> 3L
            "PUBLISHING_FINISHED" -> 4L
            "CANCELLED" -> 5L
            "ON_HIATUS" -> 6L
            else -> 0L
        }
    }

    private fun mapStatusLongToString(status: Long): String {
        return when (status) {
            1L -> "ONGOING"
            2L -> "COMPLETED"
            3L -> "LICENSED"
            4L -> "PUBLISHING_FINISHED"
            5L -> "CANCELLED"
            6L -> "ON_HIATUS"
            else -> "UNKNOWN"
        }
    }
}
