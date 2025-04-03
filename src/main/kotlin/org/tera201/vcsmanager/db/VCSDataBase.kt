package org.tera201.vcsmanager.db

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.jgit.revwalk.RevCommit
import org.tera201.vcsmanager.db.entities.*
import java.util.*


class VCSDataBase(val url:String): SQLiteCommon(url) {

    override val tableCreationQueries = mapOf(
        "Projects" to """
        CREATE TABLE IF NOT EXISTS Projects (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            filePath TEXT NOT NULL,
            UNIQUE (name, filePath) 
        );
    """.trimIndent(),

        "Authors" to """
        CREATE TABLE IF NOT EXISTS Authors (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            email TEXT NOT NULL,
            projectId INTEGER NOT NULL,
            FOREIGN KEY (projectId) REFERENCES Projects(id),
            UNIQUE (email, projectId) 
        );
    """.trimIndent(),

        "Commits" to """
        CREATE TABLE IF NOT EXISTS Commits (
            hash TEXT PRIMARY KEY,
            date INTEGER NOT NULL,
            projectSize LONG,
            projectId INTEGER NOT NULL,
            authorId TEXT NOT NULL,
            stability DOUBLE NOT NULL,
            filesAdded INTEGER NOT NULL,
            filesDeleted INTEGER NOT NULL,
            filesModified INTEGER NOT NULL,
            linesAdded INTEGER NOT NULL,
            linesDeleted INTEGER NOT NULL,
            linesModified INTEGER NOT NULL,
            changes INTEGER NOT NULL,
            changesSize INTEGER NOT NULL,
            FOREIGN KEY (projectId) REFERENCES Projects(id),
            FOREIGN KEY (authorId) REFERENCES Authors(id),
            UNIQUE (hash, projectId)
        );
    """.trimIndent(),

        "Files" to """
        CREATE TABLE IF NOT EXISTS Files (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            projectId INTEGER NOT NULL,
            filePathId INTEGER NOT NULL,
            hash TEXT,
            date INTEGER NOT NULL,
            FOREIGN KEY (projectId) REFERENCES Projects(id),
            FOREIGN KEY (filePathId) REFERENCES FilePath(id),
            FOREIGN KEY (hash) REFERENCES Commits(hash)
        );
    """.trimIndent(),

        "FilePath" to """
        CREATE TABLE IF NOT EXISTS FilePath (
            id INTEGER PRIMARY KEY,
            projectId INTEGER NOT NULL,
            filePath TEXT NOT NULL,
            FOREIGN KEY (projectId) REFERENCES Projects(id),
            UNIQUE (projectId, filePath)
        );
    """.trimIndent(),

        "BlameFiles" to """
        CREATE TABLE IF NOT EXISTS BlameFiles (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            projectId TEXT NOT NULL,
            filePathId TEXT NOT NULL,
            fileHash TEXT NOT NULL,
            lineSize LONG,
            FOREIGN KEY (projectId) REFERENCES Projects(id),
            FOREIGN KEY (filePathId) REFERENCES FilePath(id),
            FOREIGN KEY (fileHash) REFERENCES Commits(hash),
            UNIQUE (projectId, filePathId, fileHash) 
        );
    """.trimIndent(),

        "Blames" to """
        CREATE TABLE IF NOT EXISTS Blames (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            projectId INT NOT NULL,
            authorId TEXT NOT NULL,
            blameFileId INT NOT NULL,
            blameHashes TEXT NOT NULL,
            lineIds TEXT NOT NULL,
            lineCounts LONG NOT NULL,
            lineSize LONG NOT NULL,
            FOREIGN KEY (projectId) REFERENCES Projects(id),
            FOREIGN KEY (blameHashes) REFERENCES Commits(hash),
            FOREIGN KEY (authorId) REFERENCES Authors(id),
            UNIQUE (projectId, authorId, BlameFileId) 
        );
    """.trimIndent()
    )

    init {
        createTables()
    }

    fun insertProject(name: String, filePath: String):Int {
        val sql = "INSERT OR IGNORE INTO Projects(name, filePath) VALUES(?, ?)"
        return if (executeUpdate(sql, name, filePath)) getLastInsertId() else -1
    }

    fun getProjectId(projectName: String, filePath: String): Int? {
        val sql = "SELECT id FROM Projects WHERE name = ? AND filePath = ?"
        return executeQuery(sql, projectName, filePath) { getIdResult(it) }
    }

    // TODO issues with non null return
    fun insertAuthor(projectId:Int, name: String, email: String):Long? {
        val sql = "INSERT OR IGNORE INTO Authors(id, projectId, name, email) VALUES(?, ?, ?, ?)"
        return retryTransaction{
            val uniqueId = UUID.randomUUID().mostSignificantBits
            if (executeUpdate(sql, uniqueId, projectId, name, email)) uniqueId else null
        }
    }

    // TODO issues with non null return
    fun getAuthorId(projectId: Int, email: String): Long? {
        val sql = "SELECT id FROM Authors WHERE projectId = ? AND email = ?"
        return executeQuery(sql, projectId, email) { getIdResult(it) }
    }

    fun insertCommit(projectId:Int, authorId: Long, commit:RevCommit, projectSize: Long, stability: Double, fileChangeEntity: FileChangeEntity):String {
        val sql = "INSERT OR IGNORE INTO Commits(projectId, authorId, hash, date, projectSize, stability, filesAdded, filesDeleted, filesModified, linesAdded, linesDeleted, linesModified, changes, changesSize) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING hash"
        return executeQueryWithRetry(
                sql,
                projectId,
                authorId,
                commit.name,
                commit.commitTime,
                projectSize,
                stability,
                *fileChangeEntity.getSQLArgs()) { rs -> if (rs.next()) rs.getString(1) else "" }
    }

    fun getCommit(projectId: Int, hash: String): CommitEntity? {
        val sql = """
            SELECT c.*, a.email as authorEmail, a.name as authorName
            FROM Commits c
            JOIN Authors a ON a.id = c.authorId AND a.projectId = c.projectId
            WHERE hash = ? and c.projectId = ?""".trimIndent()
        return executeQuery(sql, hash, projectId) {rs ->
            if (rs.next()) CommitEntity(rs) else null
        }
    }

    fun getDeveloperInfo(projectId: Int, filePath: String) : Map<String, CommitSize>  {
        val commitSizeMap = mutableMapOf<String, CommitSize>()
        val sql = """
            SELECT c.*, a.email as authorEmail, a.name as authorName
            FROM Commits c
            JOIN Files f ON f.hash = c.hash AND f.projectId = c.projectId
            JOIN FilePath fp ON f.filePathId = fp.id
            JOIN Authors a ON a.id = c.authorId AND a.projectId = c.projectId
            WHERE c.projectId = ?
              AND fp.filePath LIKE ?
        """
        executeQuery(sql, projectId, filePath) { rs ->
            while (rs.next()) { commitSizeMap[rs.getString("hash")] = CommitSize(rs) }
        }
        return commitSizeMap
    }

    fun getCommitSizeMap(projectId: Int, filePath: String) : Map<String, CommitSize>  {
        val commitSizeMap = mutableMapOf<String, CommitSize>()
        val sql = """
            SELECT c.*, a.email as authorEmail, a.name as authorName
            FROM Commits c
            JOIN Files f ON f.hash = c.hash AND f.projectId = c.projectId
            JOIN FilePath fp ON f.filePathId = fp.id
            JOIN Authors a ON a.id = c.authorId AND a.projectId = c.projectId
            WHERE c.projectId = ?
              AND fp.filePath LIKE ?
        """
        executeQuery(sql, projectId, filePath + "%") { rs ->
            while (rs.next()) { commitSizeMap[rs.getString("hash")] = CommitSize(rs) }
        }
        return commitSizeMap
    }

    fun isCommitExist(hash: String): Boolean {
        val sql = "SELECT * FROM Commits WHERE hash = ?"
        return executeQuery(sql, hash) { it.next() }
    }

    fun insertBlameFile(projectId: Int, filePathId: Long, fileHash: String):Int {
        val sql = "INSERT OR IGNORE INTO BlameFiles(projectId, filePathId, fileHash) VALUES(?, ?, ?)"
        return if (executeUpdateWithRetry(sql, projectId, filePathId, fileHash)) getLastInsertId() else -1
    }

    // TODO issues with non null return
    fun insertFilePath(projectId: Int, filePath: String):Long {
        val sql = "INSERT OR IGNORE INTO FilePath(id, projectId, filePath) VALUES(?, ?, ?)"
        return retryTransaction{
            val uniqueId = UUID.randomUUID().mostSignificantBits
            if (executeUpdate(sql, uniqueId, projectId, filePath)) uniqueId else -1
        }
    }

    fun getFilePathId(projectId: Int, filePath: String): Long? {
        val sql = "SELECT id FROM FilePath WHERE projectId = ? AND filePath = ?"
        return executeQuery(sql, projectId, filePath) { getIdResult(it) }
    }

    fun insertFile(projectId: Int, filePathId: Long, hash: String, date: Int):Int {
        val sql = "INSERT OR IGNORE INTO Files(projectId, filePathId, hash, date) VALUES(?, ?, ?, ?)"
        return if (executeUpdate(sql, projectId, filePathId, hash, date)) getLastInsertId() else -1
    }

    fun insertFile(fileList: List<FileEntity>) {
        val sql = "INSERT OR IGNORE INTO Files(projectId, filePathId, hash, date) VALUES(?, ?, ?, ?)"
        executeBatchWithRetry(sql, fileList.map { it.getSQLArgs() })
    }

    fun insertFile(file: FileEntity):Int {
        val sql = "INSERT OR IGNORE INTO Files(projectId, filePathId, hash, date) VALUES(?, ?, ?, ?)"
        return if (executeUpdate(sql, file.projectId, file.filePathId, file.hash, file.date)) getLastInsertId() else -1
    }

    fun updateBlameFileSize(blameFileId: Int) {
        val sql = """
            UPDATE BlameFiles 
            SET lineSize = (SELECT SUM(lineSize) FROM Blames WHERE blameFileId = ? GROUP BY projectId AND blameFileId) 
            WHERE id = ?
        """.trimIndent()
        executeUpdate(sql, blameFileId, blameFileId)
    }

    fun getBlameFileId(projectId: Int, filePathId: Long, fileHash: String):Int? {
        val sql = "SELECT * FROM BlameFiles WHERE projectId = ? AND filePathId = ? AND fileHash = ?"
        return executeQuery(sql, projectId, filePathId, fileHash) { getIdResult(it) }
    }

    // TODO issues with non null return
    fun getLastFileHash(projectId: Int, filePathId: Long):String? {
        val sql = "SELECT hash FROM Files WHERE projectId = ? AND filePathId = ? ORDER BY date DESC LIMIT 1"
        return executeQuery(sql, projectId, filePathId) { if (it.next()) it.getString("hash") else ""}
    }

    fun getFirstAndLastHashForFile(projectId: Int, filePathId: Long):Pair<String, String>? {
        val sql = """
            SELECT MIN(hash) AS firstHash, MAX(hash) AS lastHash
            FROM Files
            WHERE projectId = ? AND filePathId = ?
        """.trimIndent()
        return executeQuery(sql, projectId, filePathId) { rs ->
            if (rs.next()) rs.getString("firstHash") to rs.getString("lastHash") else null
        }
    }

    fun insertBlame(blameEntity: BlameEntity) {
        val sql = "INSERT OR IGNORE INTO Blames(projectId, authorId, blameFileId, blameHashes, lineIds, lineCounts, lineSize) VALUES(?, ?, ?, ?, ?, ?, ?)"
        executeUpdate(sql, blameEntity.getSQLArgs())
    }

    fun insertBlame(blameEntities: List<BlameEntity>) {
        val sql = "INSERT OR IGNORE INTO Blames(projectId, authorId, blameFileId, blameHashes, lineIds, lineCounts, lineSize) VALUES(?, ?, ?, ?, ?, ?, ?)"
        executeBatchWithRetry(sql, blameEntities.map { it.getSQLCompressedArgs() })
    }

    fun getDevelopersByProjectId(projectId: Int): Map<String, String> {
        val developers = mutableMapOf<String, String>()
        val sql = "SELECT id, email FROM Authors WHERE projectId = ?"
        executeQuery(sql, projectId) { rs ->
            while (rs.next()) developers[rs.getString("email")] = rs.getString("id")
        }
        return developers
    }

    fun developerUpdateByBlameInfo(projectId: Int, developers: Map<String, DeveloperInfo>) {
        val sql = """
            SELECT SUM(b.lineCounts) AS lineCount, SUM(b.lineSize) AS lineSize, authors.email, string_agg(bf.filePathId, ', ') as filePaths
            FROM Blames b
            JOIN BlameFiles bf ON b.blameFileId = bf.id
            JOIN Authors authors on authors.id = b.authorId
            WHERE b.projectId = ?
            GROUP BY b.projectId, b.authorId
        """
        executeQuery(sql, projectId) { rs ->
            while (rs.next()) {
                developers[ rs.getString("email")]?.apply {
                    actualLinesSize = rs.getLong("lineSize")
                    actualLinesOwner = rs.getLong("lineCount")
                    ownerForFiles.addAll(rs.getString("filePaths").split(", "))
                }
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