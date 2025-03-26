package org.tera201.vcsmanager.scm;

import lombok.Getter;

/* TODO Naming is confusing. */
public class SCMRepository {

	@Getter
	private String repoName;
	@Getter
    private String path; /* Path in local FS. */
	@Getter
    private String headCommit; /* Most recent commit. */
	@Getter
    private String firstCommit; /* First commit. */
	@Getter
    private SCM scm;
	private String origin; /* e.g. GitHub URL */

	public SCMRepository(SCM scm, String origin, String repoName, String path, String headCommit, String firstCommit) {
		this.scm = scm;
		this.origin = origin;
		this.repoName = repoName;
		this.path = path;
		this.headCommit = headCommit;
		this.firstCommit = firstCommit;
	}

    public String getOrigin() {
		return origin == null ? path : origin;
	}

	public String getLastDir() {
		String[] dirs = path.replace("\\", "/").split("/");
		return dirs[dirs.length-1];
	}

	@Override
	public String toString() {
		return "SCMRepository [path=" + path + ", headCommit=" + headCommit + ", lastCommit=" + firstCommit + ", scm="
				+ scm + ", origin=" + origin + "]";
	}

}
