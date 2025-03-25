package org.tera201.vcsmanager.domain;

import java.io.File;

public class Modification {

	private String oldPath;
	private String newPath;
	private ModificationType type;
	private String diff;
	private String sourceCode;
	private int added;
	private int removed;

	public Modification(String oldPath, String newPath, ModificationType type, String diff, String sourceCode) {
		this.oldPath = oldPath;
		this.newPath = newPath;
		this.type = type;
		this.diff = diff;
		this.sourceCode = sourceCode;
		
		for(String line : diff.replace("\r", "").split("\n")) {
			if(line.startsWith("+") && !line.startsWith("+++")) added++;
			if(line.startsWith("-") && !line.startsWith("---")) removed++;
		}
		
	}

	public String getOldPath() {
		return oldPath;
	}

	public String getNewPath() {
		return newPath;
	}

	public ModificationType getType() {
		return type;
	}

	public String getDiff() {
		return diff;
	}

	public String getSourceCode() {
		return sourceCode;
	}

	@Override
	public String toString() {
		return "Modification [oldPath=" + oldPath + ", newPath=" + newPath + ", type=" + type
				+ "]";
	}

	public boolean wasDeleted() {
		return type.equals(ModificationType.DELETE);
	}

	public boolean fileNameEndsWith(String suffix) {
		return newPath.toLowerCase().endsWith(suffix.toLowerCase());
	}

	public boolean fileNameMatches(String regex) {
		return newPath.toLowerCase().matches(regex);
	}

	public String getFileName() {
		String thePath = newPath!=null && !newPath.equals("/dev/null") ? newPath : oldPath;
		if(!thePath.contains(File.separator)) return thePath;
		
		String[] fileName = thePath.split(File.separator);
		return fileName[fileName.length-1];
	}
	

	public int getAdded() {
		return added;
	}
	
	public int getRemoved() {
		return removed;
	}

	
}
