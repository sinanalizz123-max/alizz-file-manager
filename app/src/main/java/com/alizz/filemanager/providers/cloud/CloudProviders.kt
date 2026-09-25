package com.alizz.filemanager.providers.cloud

import android.content.Context
import java.io.File

data class CItem(val key: String, val name: String, val dir: Boolean, val size: Long)

/**
 * Uniform facade over Box / pCloud / Yandex sessions so one
 * ViewModel + one screen can serve all three.
 * dirKey: Box folder id ("0" = root), pCloud folder id ("0" = root), Yandex path ("" = root).
 */
interface CloudProvider {
    val tag: String
    val serviceName: String
    val setupHint: String
    val needsSecret: Boolean
    fun account(): String?
    fun signedIn(): Boolean
    fun credentials(): Pair<String, String>
    fun saveCredentials(key: String, secret: String)
    fun authorizeUrl(): String
    fun finishAuth(code: String)
    fun signOut()
    fun list(dirKey: String): List<CItem>
    fun mkdir(dirKey: String, name: String)
    fun rename(item: CItem, name: String)
    fun delete(item: CItem)
    fun download(item: CItem, target: File)
    fun upload(local: File, dirKey: String)
}

class BoxProvider(ctx: Context) : CloudProvider {
    private val app = ctx.applicationContext
    private val store = BoxAccountStore(app)
    private val session = BoxSession(store)

    override val tag = "box"
    override val serviceName = "Box"
    override val setupHint = "Create a Box OAuth app, register redirect fm-oauth://box, paste client ID + secret."
    override val needsSecret = true
    override fun account(): String? = store.account()
    override fun signedIn(): Boolean = account() != null
    override fun credentials(): Pair<String, String> = store.clientId() to store.clientSecret()
    override fun saveCredentials(key: String, secret: String) = store.saveCredentials(key, secret)

    override fun authorizeUrl(): String {
        val id = store.clientId()
        if (id.isEmpty()) throw java.io.IOException("Enter your Box client ID first")
        return session.authorizeUrl(id)
    }

    override fun finishAuth(code: String) {
        session.finishAuth(store.clientId(), store.clientSecret(), code)
    }

    override fun signOut() = store.clear()

    override fun list(dirKey: String): List<CItem> =
        session.list(dirKey).map { CItem(it.id, it.name, it.isDir, it.size) }

    override fun mkdir(dirKey: String, name: String) = session.mkdir(dirKey, name)

    override fun rename(item: CItem, name: String) =
        session.rename(BoxItem(item.key, item.name, item.dir, 0), name)

    override fun delete(item: CItem) =
        session.delete(BoxItem(item.key, item.name, item.dir, 0))

    override fun download(item: CItem, target: File) {
        if (item.dir) throw java.io.IOException("Folders cannot be downloaded yet")
        session.download(BoxItem(item.key, item.name, false, 0), target)
    }

    override fun upload(local: File, dirKey: String) = session.upload(local, dirKey)
}

class PCloudProvider(ctx: Context) : CloudProvider {
    private val app = ctx.applicationContext
    private val store = PCloudAccountStore(app)
    private val session = PCloudSession(store)

    override val tag = "pcloud"
    override val serviceName = "pCloud"
    override val setupHint = "Create a pCloud app, register redirect fm-oauth://pcloud, paste the client ID (secret only if your app uses one)."
    override val needsSecret = false
    override fun account(): String? = store.account()
    override fun signedIn(): Boolean = account() != null
    override fun credentials(): Pair<String, String> = store.clientId() to ""
    override fun saveCredentials(key: String, secret: String) {
        store.saveCredentials(key.ifEmpty { store.clientId() }, secret)
    }

    override fun authorizeUrl(): String {
        val id = store.clientId()
        if (id.isEmpty()) throw java.io.IOException("Enter your pCloud client ID first")
        return session.authorizeUrl(id)
    }

    override fun finishAuth(code: String) {
        session.finishAuth(store.clientId(), store.clientSecret(), code)
    }

    override fun signOut() = store.clear()

    override fun list(dirKey: String): List<CItem> =
        session.list(dirKey.toLongOrNull() ?: 0L).map { CItem(it.id.toString(), it.name, it.isDir, it.size) }

    override fun mkdir(dirKey: String, name: String) = session.mkdir(dirKey.toLongOrNull() ?: 0L, name)

    override fun rename(item: CItem, name: String) =
        session.rename(
            PCloudItem(item.key.toLongOrNull() ?: 0L, item.name, "", item.dir, 0),
            name,
        )

    override fun delete(item: CItem) =
        session.delete(PCloudItem(item.key.toLongOrNull() ?: 0L, item.name, "", item.dir, 0))

    override fun download(item: CItem, target: File) {
        if (item.dir) throw java.io.IOException("Folders cannot be downloaded yet")
        session.download(PCloudItem(item.key.toLongOrNull() ?: 0L, item.name, "", false, 0), target)
    }

    override fun upload(local: File, dirKey: String) = session.upload(local, dirKey.toLongOrNull() ?: 0L)
}

class YandexProvider(ctx: Context) : CloudProvider {
    private val app = ctx.applicationContext
    private val store = YandexAccountStore(app)
    private val session = YandexSession(store)

    override val tag = "yandex"
    override val serviceName = "Yandex Disk"
    override val setupHint = "Create an OAuth app at oauth.yandex.com, add redirect fm-oauth://yandex with Yandex.Disk access, paste client ID + secret."
    override val needsSecret = true
    override fun account(): String? = store.account()
    override fun signedIn(): Boolean = account() != null
    override fun credentials(): Pair<String, String> = store.clientId() to store.clientSecret()
    override fun saveCredentials(key: String, secret: String) {
        store.saveCredentials(key.ifEmpty { store.clientId() }, secret.ifEmpty { store.clientSecret() })
    }

    override fun authorizeUrl(): String {
        val id = store.clientId()
        if (id.isEmpty()) throw java.io.IOException("Enter your Yandex client ID first")
        return session.authorizeUrl(id)
    }

    override fun finishAuth(code: String) {
        session.finishAuth(store.clientId(), store.clientSecret(), code)
    }

    override fun signOut() = store.clear()

    override fun list(dirKey: String): List<CItem> =
        session.list(dirKey).map { CItem(it.path.ifEmpty { "/" }, it.name, it.isDir, it.size) }

    override fun mkdir(dirKey: String, name: String) = session.mkdir(dirKey, name)

    override fun rename(item: CItem, name: String) = session.rename(item.key, name)

    override fun delete(item: CItem) =
        session.delete(YandexItem(item.name, item.key, item.dir, 0, 0))

    override fun download(item: CItem, target: File) {
        if (item.dir) throw java.io.IOException("Folders cannot be downloaded yet")
        session.download(YandexItem(item.name, item.key, false, 0, 0), target)
    }

    override fun upload(local: File, dirKey: String) = session.upload(local, dirKey)
}
