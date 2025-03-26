package org.tera201.vcsmanager.util

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.tera201.vcsmanager.RepoDrillerException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object RDFileUtils {
    /** Get the absolute paths of all subdirectories within the given `path`.  */
    fun getAllDirsIn(path: String?): List<String> {
        val dir = File(path ?: return emptyList())
        return dir.listFiles()?.filter { it.isDirectory }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    /**
     * Recursively retrieves all files within the given directory, excluding `.git` and `.svn` directories.
     */
    fun getAllFilesInPath(pathToLook: String?): List<File> = getAllFilesInPath(File(pathToLook), mutableListOf())

    /* TODO This method should not have checks for .git and .svn hard-coded into it
	 *      (see isAProjectSubdirectory). */
    private fun getAllFilesInPath(directory: File, files: MutableList<File>): List<File> {
        directory.listFiles()?.forEach { file: File ->
            when {
                file.isFile -> files.add(file)
                isValidProjectDirectory(file) -> getAllFilesInPath(file, files)
            }
        }
        return files
    }

    /** Reads the content of a file and returns it as a string. */
	@JvmStatic
	fun readFile(file: File): String {
        return try {
            FileInputStream(file).use { input ->
                IOUtils.toString(input, Charset.defaultCharset())
            }
        } catch (e: Exception) {
            throw RuntimeException("Error reading file: ${file.absolutePath}", e)
        }
    }

    /** Determines whether a directory is a valid project subdirectory (not `.git` or `.svn`). */
    private fun isValidProjectDirectory(dir: File): Boolean = dir.isDirectory && dir.name !in listOf(".git", ".svn")

    /**
     * Creates a temporary file path in the specified directory or the system temp directory.
     */
    @JvmStatic
	@Throws(IOException::class)
    fun getTempPath(directory: String = FileUtils.getTempDirectoryPath()): String {
        val dir = File(directory).apply { mkdirs() }
        return try {
            File.createTempFile("RD-", "", dir).apply { delete() }.absolutePath
        } catch (e: IOException) {
            throw IOException("Error creating temp directory in $directory: $e")
        }
    }

    /**
     * Checks if the specified path exists.
     */
	@JvmStatic
	fun exists(path: Path): Boolean = path.toFile().exists()

    /**
     * Checks if the specified path is a directory.
     */
    fun isDir(path: Path): Boolean = path.toFile().isDirectory

    /**
     * Creates a directory at the specified path.
     */
    fun mkdir(path: Path): Boolean = path.toFile().mkdirs()

    /**
     * Copies a directory tree from `src` to `dest`. `dest` must not already exist.
     */
    fun copyDirTree(src: Path, dest: Path) {
        if (!exists(src)) throw RepoDrillerException("Error: Source directory $src does not exist")
        if (exists(dest)) throw RepoDrillerException("Error: Destination directory $dest already exists")

        try {
            Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
                @Throws(IOException::class)
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(dir, dest.resolve(src.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, dest.resolve(src.relativize(file)))
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            throw RepoDrillerException("Copy directory failed: $e")
        }
    }

    /**
     * Checks if the given `fileName` has an extension from the specified `extensions` list.
     */
    @JvmStatic
    fun fileNameHasIsOfType(fileName: String, extensions: List<String>): Boolean {
        return extensions.any { ext ->
            val formattedExt = if (ext.startsWith(".")) ext else ".$ext"
            fileName.endsWith(formattedExt)
        }
    }
}