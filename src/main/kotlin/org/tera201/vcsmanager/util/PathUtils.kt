package org.tera201.vcsmanager.util

object PathUtils {
    fun fullPath(path: String?): String? {
        var path = path ?: return null

        if (path.startsWith("~")) path = path.replaceFirst("^~".toRegex(), System.getProperty("user.home"))

        return path
    }
}
