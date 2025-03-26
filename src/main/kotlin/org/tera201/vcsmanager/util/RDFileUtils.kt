package org.tera201.vcsmanager.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.tera201.vcsmanager.RepoDrillerException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class RDFileUtils {

	/**
	 * Get the absolute path to all subdirs of {@code path}.
	 *
	 * @param path  Root of directory tree
	 * @return      All children of this root that are subdirs
	 */
	public static List<String> getAllDirsIn(String path) {
		File dir = new File(path);
		String[] files = dir.list();

		List<String> projects = new ArrayList<>();
		assert files != null;
		for (String file : files) {
			File possibleDir = new File(dir, file);
			if (possibleDir.isDirectory()) {
				projects.add(possibleDir.getAbsolutePath());
			}
		}

		return projects;
	}

	/**
	 * Recursively find all the files in this tree, except those in .git and .svn subdirs
	 *
	 * @param pathToLook	Tree root
	 * @return	Every file in this tree for which File.isFile(), except those in
	 */
	public static List<File> getAllFilesInPath(String pathToLook) {
		return getAllFilesInPath(pathToLook, new ArrayList<>());
	}

	/* TODO This method should not have checks for .git and .svn hard-coded into it
	 *      (see isAProjectSubdirectory). */
	/**
	 * Helper for getAllFilesInPath.
	 *
	 * @param pathToLook	Tree root
	 * @param files	Ongoing collection of files (not dirs)
	 * @return	{@code files} plus all files in the tree rooted at pathToLook
	 *          Files that are in .svn or .git dirs are ignored.
	 */
	private static List<File> getAllFilesInPath(String pathToLook, List<File> files) {
		for (File f : new File(pathToLook).listFiles()) {
			if (f.isFile())
				files.add(f);
			if (isAProjectSubdirectory(f))
				getAllFilesInPath(f.getAbsolutePath(), files);
		}
		return files;
	}

	/**
	 * Return the raw contents of this file
	 *
	 * @param f	File to read
	 * @return	Contents of file as string
	 */
	public static String readFile(File f) {
		try {
			FileInputStream input = new FileInputStream(f);
			String text = IOUtils.toString(input, Charset.defaultCharset());
			input.close();
			return text;
		} catch (Exception e) {
			throw new RuntimeException("error reading file " + f.getAbsolutePath(), e);
		}
	}

	/**
	 * True if f is a directory and doesn't look like svn or git metadata
	 *
	 * @param f
	 * @return True if it looks like a "project subdirectory"
	 */
	private static boolean isAProjectSubdirectory(File f) {
		return f.isDirectory() && !f.getName().equals(".svn") && !f.getName().equals(".git");
	}

	/**
	 * Get a unique path that does not yet exist
	 *
	 * @param directory	Where to begin the path, defaults to the system temp dir
	 * @return	Absolute path to an available file
	 * @throws IOException
	 */
	public static String getTempPath(String directory) throws IOException {
		try {
			if (directory == null)
				directory = FileUtils.getTempDirectoryPath();

			File dir = new File(directory);
			dir.mkdirs();

			File tmpFile = File.createTempFile("RD-", "", dir);
			String absPath = tmpFile.getAbsolutePath();
			tmpFile.delete();
			return absPath;
		} catch (IOException e) {
			throw new IOException("Error, couldn't create temp dir in " + directory + ": " + e);
		}
	}

	/**
	 * Does this Path exist?
	 *
	 * @param p Path to check
	 * @return True if p exists, else false
	 */
	public static boolean exists(Path p) {
		File f = p.toFile();
		return f.exists();
	}

	/**
	 * Is this Path a dir?
	 *
	 * @param p Path to check
	 * @return True if p exists and is a dir, else false
	 */
	public static boolean isDir(Path p) {
		File f = p.toFile();
		return f.isDirectory();
	}

	/**
	 * Call mkdir on p.
	 *
	 * @param p	Path to mkdir
	 * @return	True if p is a dir afterward, else false
	 */
	public static boolean mkdir(Path p) {
		File f = p.toFile();
		f.mkdirs();
		return isDir(p);
	}

	/**
	 * Copy directory tree from {@code src} to {@code dest}. {@code dest} should not exist.
	 *
	 * @param src	Where from? Must exist.
	 * @param dest	Where to? Must not exist.
	 */
	public static void copyDirTree(Path src, Path dest) {
		if (!src.toFile().exists())
			throw new RepoDrillerException("Error, src " + src + " does not exist");
		if (dest.toFile().exists())
			throw new RepoDrillerException("Error, dest " + dest + " exists already");

        try {
			Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
				@Override
			    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					Files.copy(dir, dest.resolve(src.relativize(dir)));
			        return FileVisitResult.CONTINUE;
			    }
			    @Override
			    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
			        Files.copy(file, dest.resolve(src.relativize(file)));
			        return FileVisitResult.CONTINUE;
			    }
			    @Override
			    public FileVisitResult visitFileFailed(Path file, IOException exc) {
			        return FileVisitResult.CONTINUE;
			    }
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new RepoDrillerException("copyDir failed: " + e);
		}
	}
	
	/**
	 * Check if the specified fileName ends with any of the specified file extensions.
	 * If an extension is supplied without the dot, a dot will be prepended to it.
	 * 
	 * @param fileName	 The file name or path to check
	 * @param extensions	 The list of extensions to check
	 * @return true iff fileName ends with at least one of the specified extensions
	 */
	public static boolean fileNameHasIsOfType(String fileName, List<String> extensions) {
		for (String current : extensions) {
			String extToCheck = current;
			if (!extToCheck.startsWith(".")) {
				extToCheck = "." + extToCheck;
			}
			
			if (fileName.endsWith(extToCheck)) {
				return true;
			}
		}
		
		return false;
	}
}