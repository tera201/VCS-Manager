package org.tera201.vcsmanager

import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.scm.*
import org.tera201.vcsmanager.scm.exceptions.CheckoutException
import org.tera201.vcsmanager.db.VCSDataBase
import java.io.File
import java.io.IOException

class BuildModel {
    fun createClone(gitUrl: String): SCMRepository {
        return GitRepositoryBuilder(gitUrl).buildAsRemoteSCMRepository()
    }

    fun createClone(gitUrl: String, path: String, dataBaseDirPath: String): SCMRepository {
        val vcsDataBase = VCSDataBase("$dataBaseDirPath/repository.db")
        vcsDataBase.createTables()
        return GitRepositoryBuilder(gitUrl).inTempDir(path).dateBase(vcsDataBase).buildAsRemoteSCMRepository()
    }

    fun createClone(gitUrl: String, path: String, username: String, password: String, dataBaseDirPath: String ): SCMRepository {
        val vcsDataBase = VCSDataBase("$dataBaseDirPath/repository.db")
        vcsDataBase.createTables()
        return GitRepositoryBuilder(gitUrl)
            .inTempDir(path)
            .credentials(username, password)
            .dateBase(vcsDataBase)
            .buildAsRemoteSCMRepository()
    }

    fun getRepository(gitUrl: String, path: String, dataBaseDirPath: String): SCMRepository {
        val vcsDataBase = VCSDataBase("$dataBaseDirPath/repository.db")
        vcsDataBase.createTables()
        return GitRepositoryBuilder(gitUrl).inTempDir(path).dateBase(vcsDataBase).buildAsRemoteSCMRepository()
    }

    fun getRepository(projectPath: String, dataBaseDirPath: String): SCMRepository {
        val vcsDataBase = VCSDataBase("$dataBaseDirPath/repository.db")
        vcsDataBase.createTables()
        return GitRepositoryBuilder().inTempDir(projectPath).dateBase(vcsDataBase).buildAsLocalSCMRepository()
    }

    fun getRepoNameByUrl(gitUrl: String): String {
        return GitRemoteRepository.repoNameFromURI(gitUrl)
    }

    fun createRepo(gitUrl: String): GitRemoteRepository {
        return GitRepositoryBuilder(gitUrl).buildAsRemote()
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

        fun main(args: Array<String>) {
            val projectRoot = File(".").absolutePath
            val tempDir = projectRoot.replace(".", "clonedGit")

            File(tempDir).mkdirs()

            val gitUrl = "https://github.com/arnohaase/a-foundation.git"

            val buildModel = BuildModel()

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