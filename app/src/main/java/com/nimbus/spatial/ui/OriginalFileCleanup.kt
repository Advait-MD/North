package com.nimbus.spatial.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.appcompat.app.AlertDialog

/**
 * After a file is encrypted into North, the original is still in the gallery or file manager,
 * where anyone holding the phone can open it. This offers to delete it.
 */
object OriginalFileCleanup {

    fun offer(context: Context, original: Uri, name: String) {
        AlertDialog.Builder(context)
            .setTitle("Delete the original?")
            .setMessage(
                "$name is now encrypted inside North, but the original is still on your phone, " +
                    "where anyone can open it from your gallery or files.\n\n" +
                    "If you delete it, the encrypted copy in North is the only one left, and it can't " +
                    "be recovered if North is uninstalled or its data is cleared."
            )
            .setPositiveButton("Delete original") { _, _ -> delete(context, original) }
            .setNegativeButton("Keep it", null)
            .show()
    }

    private fun delete(context: Context, original: Uri) {
        val deleted = try {
            DocumentsContract.deleteDocument(context.contentResolver, original)
        } catch (e: Exception) { // provider doesn't support deleting, or no write access
            false
        }
        if (deleted) context.toast("Original deleted")
        else context.toast("North can't delete it from here. Delete it in your gallery or file manager.", long = true)
    }
}
