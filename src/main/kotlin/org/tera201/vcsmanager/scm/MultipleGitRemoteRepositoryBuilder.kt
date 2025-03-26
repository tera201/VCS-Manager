package org.tera201.vcsmanager.scm

import org.tera201.vcsmanager.scm.GitRemoteRepository.Companion.allProjectsIn

class MultipleGitRemoteRepositoryBuilder(private val gitUrls: List<String>) : GitRemoteRepositoryBuilder() {
    fun inTempDir(tempDir: String?): MultipleGitRemoteRepositoryBuilder = apply { super.tempDir = tempDir }

    fun asBareRepos(): MultipleGitRemoteRepositoryBuilder = apply { super.bare = true }

    fun buildAsSCMRepositories(): Array<SCMRepository> = allProjectsIn(this.gitUrls, this.tempDir, this.bare)
}