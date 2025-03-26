package org.tera201.vcsmanager.scm

import org.eclipse.jgit.api.errors.GitAPIException
import org.tera201.vcsmanager.scm.GitRemoteRepository.Companion.getSingleProject
import org.tera201.vcsmanager.scm.GitRemoteRepository.Companion.singleProject
import org.tera201.vcsmanager.util.DataBaseUtil

class SingleGitRemoteRepositoryBuilder : GitRemoteRepositoryBuilder {
    private var gitUrl: String? = null

    constructor()

    constructor(gitUrl: String?) {
        this.gitUrl = gitUrl
    }

    fun inTempDir(tempDir: String?): SingleGitRemoteRepositoryBuilder {
        super.tempDir = tempDir
        return this
    }

    fun creds(username: String?, password: String?): SingleGitRemoteRepositoryBuilder {
        super.username = username
        super.password = password
        return this
    }

    fun dateBase(dateBase: DataBaseUtil?): SingleGitRemoteRepositoryBuilder {
        super.dataBaseUtil = dateBase
        return this
    }

    fun asBareRepos(): SingleGitRemoteRepositoryBuilder {
        super.bare = true
        return this
    }

    @Throws(GitAPIException::class)
    fun build(): GitRemoteRepository {
        return GitRemoteRepository(gitUrl!!, this.tempDir, this.bare, this.username, this.password, dataBaseUtil)
    }

    fun buildAsSCMRepository(): SCMRepository {
        return singleProject(
            gitUrl!!,
            this.tempDir,
            this.bare,
            this.username,
            this.password, dataBaseUtil
        )
    }

    val asSCMRepository: SCMRepository
        get() {
            return if (this.gitUrl != null) getSingleProject(
                gitUrl!!,
                tempDir,
                dataBaseUtil
            )
            else getSingleProject(this.tempDir, dataBaseUtil)
        }
}