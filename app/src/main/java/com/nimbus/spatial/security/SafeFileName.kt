package com.nimbus.spatial.security

/**
 * Turns a user-visible file name into one that is safe to use on disk: readable for the viewer
 * app, but never a path (no slashes, no "..", nothing hidden). Pure, unit-tested in FenceCheckTest.
 */
fun safeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').take(100).ifEmpty { "file" }
