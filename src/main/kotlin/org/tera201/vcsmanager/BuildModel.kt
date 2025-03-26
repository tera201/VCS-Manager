package org.tera201.vcsmanager

import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.errors.GitAPIException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.scm.GitRemoteRepository
import org.tera201.vcsmanager.scm.GitRepository
import org.tera201.vcsmanager.scm.SCMRepository
import org.tera201.vcsmanager.scm.SingleGitRemoteRepositoryBuilder
import org.tera201.vcsmanager.scm.exceptions.CheckoutException
import org.tera201.vcsmanager.util.DataBaseUtil
import java.io.File
import java.io.IOException

class BuildModel {
    fun createClone(gitUrl: String): SCMRepository {
        return GitRemoteRepository
            .hostedOn(gitUrl)
            .buildAsSCMRepository()
    }

    fun createClone(gitUrl: String, path: String, dataBaseDirPath: String): SCMRepository {
        val dataBaseUtil = DataBaseUtil("$dataBaseDirPath/repository.db")
        dataBaseUtil.create()
        return GitRemoteRepository
            .hostedOn(gitUrl)
            .inTempDir(path)
            .dateBase(dataBaseUtil)
            .buildAsSCMRepository()
    }

    fun createClone(gitUrl: String, path: String, username: String, password: String, dataBaseDirPath: String ): SCMRepository {
        val dataBaseUtil = DataBaseUtil("$dataBaseDirPath/repository.db")
        dataBaseUtil.create()
        return GitRemoteRepository
            .hostedOn(gitUrl)
            .inTempDir(path)
            .creds(username, password)
            .dateBase(dataBaseUtil)
            .buildAsSCMRepository()
    }

    fun getRepository(gitUrl: String, path: String?, dataBaseDirPath: String): SCMRepository {
        val dataBaseUtil = DataBaseUtil("$dataBaseDirPath/repository.db")
        dataBaseUtil.create()
        return GitRemoteRepository
            .hostedOn(gitUrl)
            .inTempDir(path)
            .dateBase(dataBaseUtil)
            .asSCMRepository
    }

    fun getRepository(projectPath: String?, dataBaseDirPath: String): SCMRepository {
        val dataBaseUtil = DataBaseUtil("$dataBaseDirPath/repository.db")
        dataBaseUtil.create()
        return SingleGitRemoteRepositoryBuilder()
            .inTempDir(projectPath)
            .dateBase(dataBaseUtil)
            .asSCMRepository
    }

    fun getRepoNameByUrl(gitUrl: String): String {
        return GitRemoteRepository.repoNameFromURI(gitUrl)
    }

    @Throws(GitAPIException::class)
    fun createRepo(gitUrl: String): GitRemoteRepository {
        return GitRemoteRepository
            .hostedOn(gitUrl)
            .build()
    }

    fun getBranches(repo: SCMRepository): List<String> = repo.scm.allBranches.map { it.name }

    fun getTags(repo: SCMRepository): List<String> = repo.scm.allTags.map { it.name }

    @Throws(CheckoutException::class)
    fun checkout(repo: SCMRepository, branch: String) = repo.scm.checkoutTo(branch)

    fun cleanData() {
        val csvPath = System.getProperty("user.dir") + "/analyseGit"
        val gitPath = System.getProperty("user.dir") + "/clonedGit"
        try {
            FileUtils.deleteDirectory(File(csvPath))
            FileUtils.deleteDirectory(File(gitPath))
        } catch (e: IOException) {
            log.info("Delete failed: $e")
        }
    }

    fun removeRepo() {
        val csvPath = System.getProperty("user.dir") + "/analyseGit"
        val gitPath = System.getProperty("user.dir") + "/clonedGit"
        try {
            FileUtils.deleteDirectory(File(csvPath))
            FileUtils.deleteDirectory(File(gitPath))
        } catch (e: IOException) {
            log.info("Delete failed: $e")
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(GitRepository::class.java)

        @Throws(GitAPIException::class, IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val projectRoot = File(".").absolutePath
            val csvPath = projectRoot.replace(".", "csv-generated")
            val tempDir = projectRoot.replace(".", "clonedGit")

            File(csvPath).mkdirs()

            val gitUrl = "https://github.com/arnohaase/a-foundation.git"

            val buildModel = BuildModel()
            log.info("Builded")

            val repo = buildModel.getRepository(gitUrl, "$tempDir/a-foundation", "$tempDir/db")

            val startTime = System.currentTimeMillis()
            repo.scm.dbPrepared()
            val endTime = System.currentTimeMillis()
            val executionTime = endTime - startTime
            println("dbPrepared executed in $executionTime ms")

            repo.scm.developerInfo
        }
    }
}