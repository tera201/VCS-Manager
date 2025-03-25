package org.tera201.vcsmanager.util;

public class PathUtils {

    public static String fullPath(String path) {
        if(path==null) return null;

        if(path.startsWith("~"))
            path = path.replaceFirst("^~",System.getProperty("user.home"));

        return path;
    }
}
