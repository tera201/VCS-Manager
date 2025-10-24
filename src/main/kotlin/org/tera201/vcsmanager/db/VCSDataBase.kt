package org.tera201.vcsmanager.db

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.jgit.revwalk.RevCommit
import org.jetbrains.exposed.v1.core.GroupConcat
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tera201.vcsmanager.db.entities.*
import org.tera201.vcsmanager.db.tables.*
import java.util.*


class VCSDataBase(val url:String) {
    val database: Database = Database.connect("jdbc:sqlite:$url", "org.sqlite.JDBC")

    init {
        transaction(database) {
            SchemaUtils.create(Projects, Authors, Commits, CommitMessages, Files, FilePath, BlameFiles, Blames, Branches, BranchCommitMap)
        }
    }

    fun insertProject(name: String, filePath: String):Int = transaction { Projects.insert {
            it[this.name] = name
            it[this.path] = filePath

        } get Projects.id }

    fun getProjectId(projectName: String, filePath: String): Int? = transaction { Projects.select(Projects.id)
        .where {
            Projects.name eq projectName
            Projects.path eq filePath
        }.firstOrNull()?.get(Projects.id) }

    // TODO issues with non null return
    fun insertAuthor(projectId:Int, name: String, email: String):Long = transaction {
            maxAttempts = 30
            queryTimeout = 2
            Authors.insert {
                it[this.id] = UUID.randomUUID().mostSignificantBits
                it[this.projectId] = projectId
                it[this.name] = name
                it[this.email] = email
            } get Authors.id
        }

    // TODO issues with non null return
    fun getAuthorId(projectId: Int, email: String): Long? = transaction { Authors.select(Authors.id)
        .where {
            Authors.projectId eq projectId
            Authors.email eq email
        }.firstOrNull()?.get(Authors.id)
    }

    fun insertCommitMessage(projectId: Int, commit:RevCommit) = transaction { CommitMessages.insert {
            it[this.hash] = commit.name
            it[this.projectId] = projectId
            it[this.shortMessage] = commit.shortMessage
            it[this.fullMessage] = commit.fullMessage
        } }

    fun insertCommit(projectId:Int, authorId: Long, commit:RevCommit, projectSize: Long, stability: Double, fileChangeEntity: FileChangeEntity):String = transaction { maxAttempts = 30; queryTimeout = 2
            Commits.insert {
                it[this.projectId] = projectId
                it[this.authorId] = authorId
                it[this.hash] = commit.name
                it[this.date] = commit.commitTime
                it[this.projectSize] = projectSize
                it[this.stability] = stability
                it[this.filesAdded] = fileChangeEntity.fileAdded
                it[this.filesDeleted] = fileChangeEntity.fileDeleted
                it[this.filesModified] = fileChangeEntity.fileModified
                it[this.linesAdded] = fileChangeEntity.linesAdded
                it[this.linesDeleted] = fileChangeEntity.linesDeleted
                it[this.linesModified] = fileChangeEntity.linesModified
                it[this.changes] = fileChangeEntity.changes
                it[this.changesSize] = fileChangeEntity.changesSize

            } get Commits.hash
        }

    fun getAllCommits(projectId: Int): List<CommitEntity> = transaction {
        (Commits innerJoin Authors).selectAll().where { Commits.projectId eq projectId }.map { CommitEntity(it) }
    }

    fun getCommitShortMessage(projectId: Int, hash: String): String? = transaction {
        CommitMessages.select(CommitMessages.shortMessage)
            .where { CommitMessages.hash eq hash and (CommitMessages.projectId eq projectId) }
            .firstOrNull()?.get(CommitMessages.shortMessage)
    }

    fun getCommitFullMessage(projectId: Int, hash: String): String? = transaction {
        CommitMessages.select(CommitMessages.fullMessage)
            .where { CommitMessages.hash eq hash and (CommitMessages.projectId eq projectId) }
            .firstOrNull()?.get(CommitMessages.fullMessage)
    }

    fun getCommitMessages(projectId: Int, hash: String): Pair<String, String> = transaction {
        CommitMessages.select(CommitMessages.shortMessage, CommitMessages.fullMessage)
            .where { CommitMessages.hash eq hash and (CommitMessages.projectId eq projectId) }
            .firstOrNull()?.let { Pair(it.get(CommitMessages.shortMessage), it.get(CommitMessages.fullMessage)) } ?: Pair("", "")
    }

    fun getCommit(projectId: Int, hash: String): CommitEntity? = transaction {
        (Commits innerJoin Authors).selectAll().where { Commits.projectId eq projectId and (Commits.hash eq hash) }
            .firstOrNull()?.let { CommitEntity(it) }
    }

    fun getCommit(projectId: Int, hash: String, authorEmail: String): CommitEntity? = transaction {
        (Commits innerJoin Authors).selectAll().where { Commits.projectId eq projectId and (Commits.hash eq hash) and (Authors.email eq authorEmail) }
            .firstOrNull()?.let { CommitEntity(it) }
    }

    fun getDeveloperInfo(projectId: Int, filePath: String) : Map<String, CommitSize>  = transaction {
        (Commits innerJoin Authors innerJoin Files innerJoin FilePath).selectAll()
            .where { (Commits.projectId eq projectId) and (FilePath.filePath eq filePath) }
            .associate { it[Commits.hash] to  CommitSize(it)}
    }

    fun getCommitSizeMap(projectId: Int, filePath: String) : Map<String, CommitSize> = transaction  {
        (Commits innerJoin Authors innerJoin Files innerJoin FilePath).selectAll()
            .where { (Commits.projectId eq projectId) and (FilePath.filePath like "%$filePath%") }
            .associate { it[Commits.hash] to  CommitSize(it)}
    }

    fun isCommitExist(hash: String): Boolean = transaction {
        Commits.select(Commits.hash).where { Commits.hash eq hash }.firstOrNull() != null
    }

    fun insertBlameFile(projectId: Int, filePathId: Long, fileHash: String) = transaction {
        BlameFiles.insertIgnore {
            it[this.projectId] = projectId
            it[this.filePathId] = filePathId
            it[this.fileHash] = fileHash
        }
    }

    // TODO issues with non null return
    fun insertFilePath(projectId: Int, filePath: String):Long = transaction {
        FilePath.insert {
            it[this.id] = UUID.randomUUID().mostSignificantBits
            it[this.projectId] = projectId
            it[this.filePath] = filePath
        } get FilePath.id
    }

    fun getFilePathId(projectId: Int, filePath: String): Long? = transaction {
        FilePath.select(FilePath.id)
            .where { FilePath.projectId eq projectId and (FilePath.filePath eq filePath) }.firstOrNull()?.get(FilePath.id)
    }

    fun getAllFilePaths(projectId: Int): Map<String, Long> = transaction {
        FilePath.select(FilePath.filePath, FilePath.id)
            .where { FilePath.projectId eq projectId }.associate { it[FilePath.filePath] to it[FilePath.id]}
    }

    fun insertFile(projectId: Int, filePathId: Long, hash: String, date: Int):Int = transaction {
        Files.insert {
            it[this.projectId] = projectId
            it[this.filePathId] = filePathId
            it[this.hash] = hash
            it[this.date] = date
        } get Files.id
    }

    fun insertFile(fileList: List<FileEntity>) = transaction {
        maxAttempts = 30; queryTimeout = 2
        Files.batchInsert(fileList, ignore = true) { file ->
            this[Files.projectId] = file.projectId
            this[Files.filePathId] = file.filePathId
            this[Files.hash] = file.hash
            this[Files.date] = file.date
        }
    }

    fun insertFile(file: FileEntity):Int = transaction {
        Files.insert {
            it[this.projectId] = file.projectId
            it[this.filePathId] = file.filePathId
            it[this.hash] = file.hash
            it[this.date] = file.date
        } get Files.id
    }

    fun updateBlameLineSize(blameFileId: Int) = transaction {
        val lineSum = Blames.lineSize.sum()
        val sum = Blames.select(lineSum).where { Blames.blameFileId eq blameFileId }.groupBy(Blames.projectId, Blames.blameFileId)
        BlameFiles.update({ BlameFiles.id eq blameFileId }) {
            it[lineSize] = sum
        }
    }

    fun getBlameFileId(projectId: Int, filePathId: Long, fileHash: String):Int? = transaction {
        BlameFiles.select(BlameFiles.id)
            .where { BlameFiles.projectId eq projectId and (BlameFiles.filePathId eq filePathId) and (BlameFiles.fileHash eq fileHash) }
        .firstOrNull()?.get(BlameFiles.id)
    }

    fun getFirstAndLastHashForFile(projectId: Int, filePathId: Long):Pair<String, String>? = transaction {
        val fistHash = Files.hash.min().alias("fistHash")
        val lastHash = Files.hash.max().alias("lastHash")
        Files.select(fistHash, lastHash).where { Files.projectId eq projectId and (Files.filePathId eq filePathId) }
            .firstNotNullOfOrNull { row ->
                val first = row[fistHash]
                val last = row[lastHash]
                if (first != null && last != null) last to first else null
            }
    }

    fun insertBlame(blameEntity: BlameEntity) = transaction {
        Blames.insert {
            it[Blames.projectId] = blameEntity.projectId
            it[Blames.authorId] = blameEntity.authorId
            it[Blames.blameFileId] = blameEntity.blameFileId
            it[Blames.lineIds] = convertListToJson(blameEntity.lineIds)
            it[Blames.lineCounts] = blameEntity.lineIds.size.toLong()
            it[Blames.lineSize] = blameEntity.lineSize

        }
    }

    fun insertBlame(blameEntities: List<BlameEntity>) = transaction {
        maxAttempts = 30; queryTimeout = 2
        Blames.batchInsert(blameEntities, ignore = true) { blame ->
            this[Blames.projectId] = blame.projectId
            this[Blames.authorId] = blame.authorId
            this[Blames.blameFileId] = blame.blameFileId
            this[Blames.lineIds] = convertListToJson(blame.lineIds)
            this[Blames.lineCounts] = blame.lineIds.size.toLong()
            this[Blames.lineSize] = blame.lineSize
        }
    }

    fun isBlameExist(projectId: Int, blameFileId: Int): Boolean = transaction {
        Blames.select(Blames.id).where { Blames.projectId eq projectId and (Blames.blameFileId eq blameFileId) }.firstOrNull() != null
    }

    fun isBlameExist(projectId: Int, filePathId: Long, fileHash: String): Boolean = transaction {
        (Blames innerJoin BlameFiles).select(Blames.id).where { Blames.projectId eq projectId and (BlameFiles.filePathId eq filePathId) and (BlameFiles.fileHash eq fileHash) }.firstOrNull() != null
    }

    fun insertBranch(projectId: Int, branchName: String):Int = transaction {
        Branches.insert {
            it[this.projectId] = projectId
            it[this.name] = branchName
        } get Branches.id
    }

    fun insertBranches(projectId: Int, branches: List<String>) = transaction {
        maxAttempts = 30; queryTimeout = 2
        Branches.batchInsert(branches, ignore = true) {
            this[Branches.projectId] = projectId
            this[Branches.name] = it
        }
    }

    fun getBranchId(projectId: Int, branchName: String): Int? = transaction {
        Branches.select(Branches.id).where { Branches.projectId eq projectId and (Branches.name eq branchName) }.firstOrNull()?.get(Branches.id)
    }

    fun insertBranchCommit(projectId: Int, branchId: Int, commitHash: String) = transaction {
        maxAttempts = 30; queryTimeout = 2
        BranchCommitMap.insertIgnore {
            it[this.projectId] = projectId
            it[this.branchId] = branchId
            it[this.commitHash] = commitHash
        }
    }

    fun getBranchCommitMap(projectId: Int, branchId: Int): List<String> = transaction {
        BranchCommitMap.select(BranchCommitMap.commitHash)
            .where { BranchCommitMap.projectId eq projectId and (BranchCommitMap.branchId eq branchId) }.mapNotNull { it[BranchCommitMap.commitHash] }
    }

    fun getDevelopersByProjectId(projectId: Int): Map<String, Long> = transaction {
        Authors.select(Authors.email, Authors.id).where { Authors.projectId eq projectId }.associate { it[Authors.email] to it[Authors.id] }
    }

    fun developerUpdateByBlameInfo(projectId: Int, developers: Map<String, DeveloperInfo>) = transaction {
        val lineCountSum = Blames.lineCounts.sum().alias("lineCountSum")
        val lineSizeSum = Blames.lineSize.sum().alias("lineSizeSum")
        val filePathsId = GroupConcat(
            BlameFiles.filePathId.castTo<String>(TextColumnType()), ", ", false).alias("filePathsId")
        (Blames innerJoin  BlameFiles innerJoin  Authors).select(lineSizeSum, lineCountSum, Authors.email, filePathsId)
            .groupBy(Blames.authorId, Blames.projectId).where { Blames.projectId eq projectId }.forEach {
                developers[ it[Authors.email] ]?.apply {
                    actualLinesSize = it[lineSizeSum] ?: 0
                    actualLinesOwner = it[lineCountSum] ?: 0
                    ownerForFiles.addAll(it[filePathsId].split(", "))
                }
            }
    }

    private fun convertListToJson(list: List<Any>): String {
        val mapper = ObjectMapper()
        var jsonString: String = "{}"
        try {
            jsonString = mapper.writeValueAsString(list)
        } catch (e: JsonProcessingException) {
            e.printStackTrace()
        }
        return jsonString
    }
}