package org.tera201.vcsmanager.scm

import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.RepoDrillerException
import org.tera201.vcsmanager.util.DataBaseUtil
import org.tera201.vcsmanager.util.RDFileUtils.exists
import org.tera201.vcsmanager.util.RDFileUtils.getTempPath
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A GitRepository that knows how to clone a remote repo and clean up after itself.
 * Instantiating a GitRemoteRepository will clone the specified repo, which
 *  - is expensive
 *  - may throw an exception
 */
/* TODO Requiring cloning at instantiation-time is not "lightweight".
 *      It means the user won't get any results until after cloning every repo being analyzed.
 *      I suggest we only actually clone when the clone() method of an SCM is invoked.
 *      We should override GitRepository's cheap "copy" implementation of clone() and actually clone then.
 *      In this case I suppose we might want to differentiate between a "lightweight" SCM and a "full" SCM? Hmm.
 *      */
class GitRemoteRepository (
    private val uri: String,
    destination: String? = null,
    bare: Boolean = false,
    private val username: String? = null,
    private val password: String? = null,
    override var dataBaseUtil: DataBaseUtil? = null
) : GitRepository() {

    private var hasLocalState = false
    private var repoName: String = repoNameFromURI(uri)
    private var path: Path = Paths.get(destination?.let { "$it/$repoName" } ?: (getTempPath() + "-" + repoName))
    override var projectId: Int? = null
    private var bareClone = bare

    init {
        try {
            // If dataBaseUtil is not null, insert and retrieve project information
            dataBaseUtil?.let {
                projectId = it.getProjectId(repoName, path.toString()) ?: it.insertProject(repoName, path.toString())
            }

            if (exists(path)) {
                throw RepoDrillerException("Error, path $path already exists")
            }

            cloneGitRepository(uri, path, bare)
            this.setPath(path.toString())
            this.setFirstParentOnly(true) // TODO: Add logic if needed

            hasLocalState = true
        } catch (e: Exception) {
            log.error("Unsuccessful git remote repository initialization", e)
            throw RepoDrillerException(e)
        }

        log.info("Cloned $uri to $path")
    }

    /**
     * Clone a git repository.
     *
     * @param uri    Where from?
     * @param dest    Where to?
     * @param bare    Bare (metadata-only) or full?
     * @throws GitAPIException
     */
    private fun cloneGitRepository(uri: String?, dest: Path, bare: Boolean) {
        val directory = File(dest.toString())
        val credentialsProvider: CredentialsProvider? = if (username != null) {
            UsernamePasswordCredentialsProvider(username, password)
        } else null

        log.info("Cloning Remote Repository $uri into $path")
        Git.cloneRepository()
            .setURI(uri)
            .setBare(bare)
            .setDirectory(directory)
            .setCredentialsProvider(credentialsProvider)
            .setCloneAllBranches(true)
            .setNoCheckout(false)
            .call()
    }

    override fun clone(dest: Path): SCM {
        try {
            log.info("Cloning $uri to $dest")
            cloneGitRepository(uri, dest, bareClone)
            return GitRepository(dest.toString())
        } catch (e: GitAPIException) {
            throw RepoDrillerException("Clone failed: $e")
        }
    }

    override fun delete() {
        if (hasLocalState) {
            try {
                FileUtils.deleteDirectory(File(path.toString()))
                hasLocalState = false
            } catch (e: IOException) {
                log.error("Couldn't delete GitRemoteRepository with path $path")
                log.error(e.message)
            }
        }
    }

    fun getRepositoryPath(): String = path.toString()

    companion object {
        /* Constants. */
        const val URL_SUFFIX: String = ".git"

        private val log: Logger = LoggerFactory.getLogger(GitRemoteRepository::class.java)

        /**
         * Extract a git repo name from its URL.
         *
         * @param uri
         * @return
         */
        fun repoNameFromURI(uri: String): String {
            val lastSlashIx = uri.lastIndexOf("/")
            var lastSuffIx = uri.lastIndexOf(URL_SUFFIX)
            if (lastSuffIx < 0) lastSuffIx = uri.length
            if (lastSlashIx < 0 || lastSuffIx <= lastSlashIx) {
                throw RepoDrillerException("Error, ill-formed url: $uri")
            }
            return uri.substring(lastSlashIx + 1, lastSuffIx)
        }

        fun singleProject(url: String): SCMRepository =
            singleProject(url, null, false)

        fun singleProject(url: String, rootPath: String?, bare: Boolean): SCMRepository =
            GitRemoteRepository(url, rootPath, bare).info()

        fun singleProject(url: String, rootPath: String?, bare: Boolean, username: String?, password: String?, dataBaseUtil: DataBaseUtil?): SCMRepository =
            GitRemoteRepository(url, rootPath, bare, username, password, dataBaseUtil).info()

        fun getSingleProject(url: String, rootPath: String?, dataBaseUtil: DataBaseUtil?): SCMRepository =
            GitRemoteRepository(url, rootPath, dataBaseUtil = dataBaseUtil).info

        fun getSingleProject(projectPath: String?, dataBaseUtil: DataBaseUtil?): SCMRepository =
            GitRepository(projectPath.orEmpty(), true, dataBaseUtil).info

        fun allProjectsIn(urls: List<String>): Array<SCMRepository> =
            allProjectsIn(urls, null, false)

        fun allProjectsIn(urls: List<String>, rootPath: String?, bare: Boolean): Array<SCMRepository> {
            return urls.map { singleProject(it, rootPath, bare) }.toTypedArray()
        }

        fun hostedOn(gitUrl: String): SingleGitRemoteRepositoryBuilder =
            SingleGitRemoteRepositoryBuilder(gitUrl)

        fun hostedOn(gitUrls: List<String>): MultipleGitRemoteRepositoryBuilder =
            MultipleGitRemoteRepositoryBuilder(gitUrls)
    }
}
