package eu.kanade.tachiyomi.extension.manga.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import mihon.domain.extensionrepo.manga.interactor.UpdateMangaExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class MangaExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetMangaExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateMangaExtensionRepo by injectLazy()
    private val extensionManager: MangaExtensionManager by injectLazy()
    private val json: Json by injectLazy()
    private val protoBuf: ProtoBuf by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong("last_ext_check", 0)
    }

    suspend fun findExtensions(): List<MangaExtension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<MangaExtension.Available> {
        val repoBaseUrl = extRepo.baseUrl.trimEnd('/')
        val rootRepoUrl = repoBaseUrl
            .removeSuffix("/index.pb")
            .removeSuffix("/index.min.json")
            .removeSuffix("/repo.json")
            .trimEnd('/')

        // 1. Intentar primero con index.pb (formato oficial de Keiyoushi y repos modernos)
        val pbUrl = if (repoBaseUrl.endsWith(".pb")) repoBaseUrl else "$rootRepoUrl/index.pb"
        try {
            val response = networkService.client
                .newCall(GET(pbUrl))
                .awaitSuccess()

            val source = response.body.source().decompressIfGzipped()
            val bytes = source.readByteArray()

            val store = try {
                protoBuf.decodeFromByteArray<NetworkMangaExtensionStore>(bytes)
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG, e) { "Failed decoding NetworkMangaExtensionStore, trying ExtensionList" }
                val list = protoBuf.decodeFromByteArray<NetworkMangaExtensionStore.ExtensionList>(bytes)
                NetworkMangaExtensionStore(extensionList = list)
            }

            val extensionList = if (store.extensionListUrl != null) {
                try {
                    val resp = networkService.client.newCall(GET(store.extensionListUrl)).awaitSuccess()
                    val listSource = resp.body.source().decompressIfGzipped()
                    val listBytes = listSource.readByteArray()
                    try {
                        protoBuf.decodeFromByteArray<NetworkMangaExtensionStore.ExtensionList>(listBytes)
                    } catch (_: Exception) {
                        json.decodeFromString<NetworkMangaExtensionStore.ExtensionList>(listBytes.decodeToString())
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to fetch extensionListUrl ${store.extensionListUrl}" }
                    store.extensionList
                }
            } else {
                store.extensionList
            }

            val extensions = extensionList?.extensions.orEmpty()
            if (extensions.isNotEmpty()) {
                val list = extensions.mapNotNull { ext ->
                    val libVersion = ext.extensionLib.toDoubleOrNull()
                        ?: ext.versionName.substringBeforeLast('.').toDoubleOrNull()
                        ?: 1.4
                    if (libVersion < MangaExtensionLoader.LIB_VERSION_MIN || libVersion > MangaExtensionLoader.LIB_VERSION_MAX) {
                        return@mapNotNull null
                    }
                    val apkUrl = if (ext.resources.apkUrl.startsWith("http://") || ext.resources.apkUrl.startsWith("https://")) {
                        ext.resources.apkUrl
                    } else {
                        "$rootRepoUrl/apk/${ext.resources.apkUrl}"
                    }
                    val iconUrl = if (ext.resources.iconUrl.startsWith("http://") || ext.resources.iconUrl.startsWith("https://")) {
                        ext.resources.iconUrl
                    } else {
                        "$rootRepoUrl/icon/${ext.packageName}.png"
                    }
                    val lang = ext.sources.map { it.language }.toSet()
                    MangaExtension.Available(
                        name = ext.name.substringAfter("Tachiyomi: ").substringAfter("Mihon: "),
                        pkgName = ext.packageName,
                        versionName = ext.versionName,
                        versionCode = ext.versionCode,
                        libVersion = libVersion,
                        lang = if (lang.size == 1) lang.first() else "all",
                        isNsfw = ext.contentWarning == NetworkMangaExtensionStore.ContentWarning.MIXED ||
                            ext.contentWarning == NetworkMangaExtensionStore.ContentWarning.NSFW,
                        sources = ext.sources.map { src ->
                            MangaExtension.Available.MangaSource(
                                id = src.id,
                                lang = src.language,
                                name = src.name,
                                baseUrl = src.homeUrl,
                            )
                        },
                        apkName = apkUrl,
                        iconUrl = iconUrl,
                        repoUrl = rootRepoUrl,
                    )
                }
                if (list.isNotEmpty()) {
                    return list
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG, e) { "Failed to get index.pb from $pbUrl, attempting index.min.json" }
        }

        // 2. Fallback a index.min.json (repositorios antiguos)
        val jsonUrl = "$rootRepoUrl/index.min.json"
        return try {
            val response = networkService.client
                .newCall(GET(jsonUrl))
                .awaitSuccess()

            with(json) {
                response
                    .parseAs<List<ExtensionJsonObject>>()
                    .toExtensions(rootRepoUrl)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $rootRepoUrl" }
            emptyList()
        }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<MangaExtension.Installed>? {
        // Limit checks to once a day at most
        if (fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = MangaExtensionLoader.loadMangaExtensions(context)
            .filterIsInstance<MangaLoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<MangaExtension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<MangaExtension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= MangaExtensionLoader.LIB_VERSION_MIN && libVersion <= MangaExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                MangaExtension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }
    }

    fun getApkUrl(extension: MangaExtension.Available): String {
        return if (extension.apkName.startsWith("http://") || extension.apkName.startsWith("https://")) {
            extension.apkName
        } else {
            "${extension.repoUrl}/apk/${extension.apkName}"
        }
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }
        return if (isGzip) gzip().buffer() else this
    }
}

@Serializable
private data class NetworkMangaExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact = Contact(),
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList())

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources = Resources(),
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0L,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0L,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    @Suppress("Unused")
    enum class ContentWarning {
        @ProtoNumber(0)
        UNSPECIFIED,

        @ProtoNumber(1)
        SAFE,

        @ProtoNumber(2)
        MIXED,

        @ProtoNumber(3)
        NSFW,
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> MangaExtension.Available.MangaSource = {
    MangaExtension.Available.MangaSource(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
