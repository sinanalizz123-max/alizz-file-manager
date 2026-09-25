package com.alizz.filemanager.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

const val SHIZUKU_REQUEST_CODE = 11041
private const val SERVICE_VERSION = 1

enum class ShizukuStatus { NOT_INSTALLED, DENIED, GRANTED, BOUND }

/** App Shizuku provider endpoint (required by the Shizuku manager). */
class AppShizukuProvider : ShizukuProvider()

/** User service running with system privileges via Shizuku. */
class PrivilegedService : android.app.Service() {
    private val binder = object : IPrivilegedService.Stub() {
        override fun deleteRecursively(path: String): Boolean = deleteTree(File(path))
        override fun canWrite(path: String): Boolean {
            val f = File(path)
            val probe = if (f.isDirectory) File(f, ".fm-write-probe") else f
            return try {
                if (probe.exists()) probe.canWrite()
                else {
                    val parent = probe.parentFile ?: return false
                    val test = File(parent, ".fm-write-probe")
                    val ok = test.createNewFile()
                    test.delete()
                    ok
                }
            } catch (e: Exception) {
                false
            }
        }

        override fun versionCode(): Long = SERVICE_VERSION.toLong()

        private fun deleteTree(file: File): Boolean {
            if (file.isDirectory) {
                for (child in file.listFiles() ?: return false) if (!deleteTree(child)) return false
            }
            return file.delete()
        }
    }

    override fun onBind(intent: android.content.Intent?): IBinder = binder
}

/**
 * Shizuku integration: detection, permission, privileged delete.
 * Disabled by default — the UI must explicitly enable it, and every
 * privileged call falls back to normal APIs on failure.
 */
class ShizukuHub(context: Context) {
    private val app = context.applicationContext

    var status by mutableStateOf(queryStatus())
        private set
    var boundService: IPrivilegedService? = null
        private set
    private var lastArgs: Shizuku.UserServiceArgs? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            status = if (grantResult == PackageManager.PERMISSION_GRANTED) ShizukuStatus.GRANTED else ShizukuStatus.DENIED
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind()
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                status = ShizukuStatus.GRANTED
                return
            }
            try {
                val service = IPrivilegedService.Stub.asInterface(binder)
                if (service.versionCode() != SERVICE_VERSION.toLong()) {
                    status = ShizukuStatus.GRANTED
                    return
                }
                boundService = service
                status = ShizukuStatus.BOUND
            } catch (e: Exception) {
                status = ShizukuStatus.GRANTED
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            status = queryStatus()
        }
    }

    fun refresh() {
        status = queryStatus()
    }

    fun start() {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
        if (status == ShizukuStatus.GRANTED) bind()
    }

    fun stop() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
        }
        unbind()
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                status = ShizukuStatus.NOT_INSTALLED
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                status = ShizukuStatus.GRANTED
                bind()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } catch (e: Exception) {
            status = ShizukuStatus.NOT_INSTALLED
        }
    }

    private fun bind() {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(app.packageName, PrivilegedService::class.java.name),
            ).daemon(false).processNameSuffix("shizuku").version(SERVICE_VERSION)
            lastArgs = args
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            status = ShizukuStatus.GRANTED
        }
    }

    private fun unbind() {
        boundService = null
        try {
            val args = lastArgs
            if (args != null) Shizuku.unbindUserService(args, serviceConnection, true)
            lastArgs = null
        } catch (e: Exception) {
        }
    }

    private fun queryStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) return ShizukuStatus.NOT_INSTALLED
            if (boundService != null) return ShizukuStatus.BOUND
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShizukuStatus.GRANTED
            else ShizukuStatus.DENIED
        } catch (e: Exception) {
            ShizukuStatus.NOT_INSTALLED
        }
    }

    companion object {
        fun isManagerInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
