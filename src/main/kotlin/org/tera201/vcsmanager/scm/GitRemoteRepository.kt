package org.tera201.vcsmanager.scm

import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.VCSException
import org.tera201.vcsmanager.util.DataBaseUtil
import org.tera201.vcsmanager.util.PathUtils
import org.tera201.vcsmanager.util.RDFileUtils.exists
import org.tera201.vcsmanager.util.RDFileUtils.getTempPath
import java.io.File
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
class GitRemoteRepository(
    private val uri: String,
    destination: String? = null,
    bare: Boolean = false,
    private val username: String? = null,
    private val password: String? = null,
    override var dataBaseUtil: DataBaseUtil? = null
) : GitRepository() {
    private var hasLocalState = false
    private var repoName: String = repoNameFromURI(uri)
    private var destinationPath: Path =
        Paths.get(destination?.let { "$it/$repoName" } ?: (getTempPath() + "-" + repoName))
    override var projectId: Int? = null
    private var bareClone = bare

    init {
        try {
            dataBaseUtil?.let {
                projectId = it.getProjectId(repoName, destinationPath.toString()) ?: it.insertProject(
                    repoName,
                    destinationPath.toString()
                )
            }

            if (exists(destinationPath)) {
                throw VCSException("Error, path $destinationPath already exists")
            }

            cloneGitRepository(uri, destinationPath, bare)
            this.path = PathUtils.fullPath(destinationPath.toString())
            this.firstParentOnly = true // TODO: Add logic if needed

            hasLocalState = true
        } catch (e: Exception) {
            throw VCSException("Unsuccessful git remote repository initialization", e)
        }

        log.info("Cloned $uri to $destinationPath")
    }

    /** Clone a git repository. */
    private fun cloneGitRepository(uri: String, dest: Path, bare: Boolean) {
        val directory = File(dest.toString())
        val credentialsProvider: CredentialsProvider? = if (username != null) {
            UsernamePasswordCredentialsProvider(username, password)
        } else null

        log.info("Cloning Remote Repository $uri into $destinationPath")
        Git.cloneRepository()
            .setURI(uri)
            .setBare(bare)
            .setDirectory(directory)
            .setCredentialsProvider(credentialsProvider)
            .setCloneAllBranches(true)
            .setNoCheckout(false)
            .call()
    }

    override fun clone(dest: Path): SCM = runCatching {
        log.info("Cloning $uri to $dest")
        cloneGitRepository(uri, dest, bareClone)
        return GitRepository(dest.toString())
    }.getOrElse { throw VCSException("Clone failed: $it") }

    override fun delete() {
        if (hasLocalState) {
            runCatching {
                FileUtils.deleteDirectory(File(destinationPath.toString()))
                hasLocalState = false
            }.getOrElse {
                log.error("Couldn't delete GitRemoteRepository with path $destinationPath")
                log.error(it.message)
            }
        }
    }

    companion object {
        const val URL_SUFFIX: String = ".git"

        private val log: Logger = LoggerFactory.getLogger(GitRemoteRepository::class.java)

        /** Extract a git repo name from its URL. */
        fun repoNameFromURI(uri: String): String {
            return uri.substringAfterLast("/").substringBefore(URL_SUFFIX)
        }
    }
}
