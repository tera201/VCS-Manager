package org.tera201.vcsmanager.util

object PathUtils {
    fun fullPath(path: String): String {
        if (path.startsWith("~")) path.replaceFirst("^~".toRegex(), System.getProperty("user.home"))
        return path
    }
}
