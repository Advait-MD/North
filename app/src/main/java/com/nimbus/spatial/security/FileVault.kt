@file:Suppress("DEPRECATION") // see the note on FileVault

package com.nimbus.spatial.security

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.nimbus.spatial.data.GeofenceArea
import java.io.File
import java.io.FileNotFoundException

/**
 * Encrypted storage for attached files.
 *
 * Attaching a file copies it into app-private storage, encrypted with Jetpack Security's
 * EncryptedFile (AES-256-GCM, streaming in 4 KB segments). The key lives in the Android Keystore
 * (hardware-backed where the phone supports it) and can't be exported, so a copied vault
 * folder is useless on any other phone, or after the app is reinstalled.
 *
 * The first attach also pins the fence's location in an encrypted record on this phone.
 * LiveLocationGate checks against that pin, not Firestore, so recreating or moving a fence in
 * the cloud can't move where its files unlock. GCM also makes the pin tamper-evident.
 *
 * Opening a file decrypts it into a temporary file under cache/decrypted/{areaId}/, shared with
 * the viewer app through FileProvider. Those temporary files are wiped when the user leaves the
 * fence, comes back to the file list, or reopens the app.
 *
 * Note: androidx.security:security-crypto 1.1.0 is deprecated and won't get new releases. It
 * still works. Every use of it is in this file, so replacing it later only touches this file.
 *
 * All functions do disk I/O: call them off the main thread.
 */
class FileVault(context: Context, userId: String) {

    private val appContext = context.applicationContext
    private val vaultDir = File(appContext.filesDir, "vault/$userId")
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    /** Reads [source] and stores an encrypted copy. On failure, nothing is left behind. */
    fun encrypt(source: Uri, areaId: String, fileId: String) {
        val target = encryptedPath(areaId, fileId)
        target.parentFile?.mkdirs()
        target.delete() // EncryptedFile refuses to overwrite
        try {
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw FileNotFoundException("Can't read the selected file")
            input.use { plain ->
                encryptedFile(target).openFileOutput().use { encrypted -> plain.copyTo(encrypted) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Decrypts into a temporary file and returns a content:// URI that a viewer app can read. */
    fun decryptForViewing(areaId: String, fileId: String, displayName: String): Uri {
        val source = encryptedPath(areaId, fileId)
        if (!source.exists()) throw FileNotFoundException("This file isn't stored on this phone")

        val plain = File(decryptedDir(appContext, areaId), "$fileId/${safeFileName(displayName)}")
        plain.parentFile?.mkdirs()
        encryptedFile(source).openFileInput().use { input ->
            plain.outputStream().use { output -> input.copyTo(output) }
        }
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", plain)
    }

    /** Records where [fence] is, the first time only. Later calls never change the pin. */
    fun pinFence(fence: GeofenceArea) {
        val pin = pinPath(fence.id)
        if (pin.exists()) return
        pin.parentFile?.mkdirs()
        encryptedFile(pin).openFileOutput().use { out ->
            out.write("${fence.latitude},${fence.longitude},${fence.radius}".toByteArray())
        }
    }

    /** The fence as pinned on this phone, or null if no file was ever attached here. */
    fun pinnedFence(fence: GeofenceArea): GeofenceArea? {
        val pin = pinPath(fence.id)
        if (!pin.exists()) return null
        val parts = encryptedFile(pin).openFileInput().use { it.readBytes().decodeToString() }.split(",")
        val latitude = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val longitude = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        val radius = parts.getOrNull(2)?.toFloatOrNull() ?: return null
        return fence.copy(latitude = latitude, longitude = longitude, radius = radius)
    }

    fun delete(areaId: String, fileId: String) {
        encryptedPath(areaId, fileId).delete()
    }

    fun deleteArea(areaId: String) {
        File(vaultDir, areaId).deleteRecursively()
        clearDecrypted(appContext, areaId)
    }

    private fun encryptedPath(areaId: String, fileId: String) = File(vaultDir, "$areaId/$fileId.enc")

    private fun pinPath(areaId: String) = File(vaultDir, "$areaId/fence.pin")

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(appContext, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()

    companion object {
        /** Wipes decrypted copies for one fence, or for every fence when [areaId] is null. */
        fun clearDecrypted(context: Context, areaId: String? = null) {
            val root = File(context.applicationContext.cacheDir, "decrypted")
            (if (areaId == null) root else File(root, areaId)).deleteRecursively()
        }

        private fun decryptedDir(context: Context, areaId: String) =
            File(context.cacheDir, "decrypted/$areaId")
    }
}
