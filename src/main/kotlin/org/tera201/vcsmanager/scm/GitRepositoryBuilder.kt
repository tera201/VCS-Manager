package org.tera201.vcsmanager.scm

import org.tera201.vcsmanager.util.DataBaseUtil

class GitRepositoryBuilder {
    private var gitUrl: String? = null
    private var tempDir: String? = null
    private var bare: Boolean = false
    private var username: String? = null
    private var password: String? = null
    private var dataBaseUtil: DataBaseUtil? = null

    constructor()

    constructor(gitUrl: String) {
        this.gitUrl = gitUrl
    }

    fun inTempDir(tempDir: String): GitRepositoryBuilder = apply { this.tempDir = tempDir }

    fun credentials(username: String, password: String): GitRepositoryBuilder = apply {
        this.username = username;
        this.password = password
    }

    fun dateBase(dateBase: DataBaseUtil): GitRepositoryBuilder  = apply { dataBaseUtil = dateBase }

    fun asBareRepos(): GitRepositoryBuilder = apply { bare = true }

    fun buildAsRemote(): GitRemoteRepository =
        GitRemoteRepository(gitUrl!!, this.tempDir, this.bare, this.username, this.password, dataBaseUtil)

    fun buildAsLocal(): GitRepository = GitRepository("", true, dataBaseUtil)

    fun buildAsRemoteSCMRepository(): SCMRepository =
        GitRemoteRepository(gitUrl!!, tempDir, bare, username, password, dataBaseUtil).info

    fun buildAsLocalSCMRepository(): SCMRepository  = GitRepository("", true, dataBaseUtil).info
}