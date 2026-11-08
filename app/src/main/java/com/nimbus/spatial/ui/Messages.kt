package com.nimbus.spatial.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

/**
 * Shows a short message: a Snackbar at the bottom of the screen inside an activity,
 * or a Toast anywhere else.
 */
fun Context.showMessage(message: String, long: Boolean = false) {
    val root = findActivity()?.takeUnless { it.isFinishing }?.findViewById<View>(android.R.id.content)
    if (root != null) {
        Snackbar.make(root, message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).show()
    } else {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}

/** Views often hold a theme wrapper around their activity rather than the activity itself. */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
