package org.tera201.vcsmanager.scm;

import org.tera201.vcsmanager.util.DataBaseUtil;

public abstract class GitRemoteRepositoryBuilder {

	protected String tempDir;
	protected boolean bare = false;
	protected String username;
	protected String password;
	protected DataBaseUtil dataBaseUtil;
	
}