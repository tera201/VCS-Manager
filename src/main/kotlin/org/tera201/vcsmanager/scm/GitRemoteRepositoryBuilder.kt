package org.tera201.vcsmanager.scm

import org.tera201.vcsmanager.util.DataBaseUtil

abstract class GitRemoteRepositoryBuilder {
	protected var tempDir: String? = null
	protected var bare: Boolean = false
    protected var username: String? = null
    protected var password: String? = null
    protected var dataBaseUtil: DataBaseUtil? = null
}