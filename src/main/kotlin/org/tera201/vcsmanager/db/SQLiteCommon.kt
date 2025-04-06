package org.tera201.vcsmanager.db

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.*
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

abstract class SQLiteCommon(url:String) {
    val conn: Connection
    abstract val tableCreationQueries: Map<String, String>
    private val log: Logger = LoggerFactory.getLogger(SQLiteCommon::class.java)

    init {
        runCatching { Class.forName("org.sqlite.JDBC") }
            .onFailure { throw RuntimeException("SQLite JDBC driver not found!", it) }
        conn = createDatabaseConnection("jdbc:sqlite:$url")
    }

    protected fun tableExists(tableName: String): Boolean {
        val query = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
        conn.prepareStatement(query).use { stmt ->
            stmt.setString(1, tableName)
            stmt.executeQuery().use { rs ->
                return rs.next() // If there is a result, the table exists
            }
        }
    }

    protected fun createDatabaseConnection(dbUrl: String): Connection =
        runCatching { DriverManager.getConnection(dbUrl).also { enableForeignKeys(it) } }
            .getOrElse { throw RuntimeException("Error connecting to the database: ${it.message}", it) }

    fun closeConnection() {
        conn.close()
    }

    /** Creates necessary tables in the SQLite database if they do not already exist. */
    fun createTables() {
        try {
            conn.createStatement().use { stmt ->
                for ((tableName, sqlQuery) in tableCreationQueries) {
                    if (!tableExists(tableName)) {
                        stmt.execute(sqlQuery)
                        log.debug("Table $tableName created successfully.")
                    }
                }
            }
        } catch (e: SQLException) {
            log.error("Error creating tables: ${e.message}")
        }
    }

    /** Drops tables from the SQLite database if they exist. */
    fun dropTables() {
        try {
            conn.createStatement().use { stmt ->
                for (table in tableCreationQueries.keys) {
                    stmt.execute("DROP TABLE IF EXISTS $table")
                    log.debug("Dropped table: $table")
                }
            }
        } catch (e: SQLException) {
            log.error("Error dropping tables: ${e.message}")
        }
    }

    protected fun enableForeignKeys(connection: Connection) {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON;") }
    }

    /** Helper function to set parameters for PreparedStatements */
    protected fun setParams(pstmt: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, param ->
            when (param) {
                is Int -> pstmt.setInt(index + 1, param)
                is Int? -> pstmt.setIntOrNull(index + 1, param)
                is String -> pstmt.setString(index + 1, param)
                is Long -> pstmt.setLong(index + 1, param)
                else -> pstmt.setObject(index + 1, param)
            }
        }
    }

    /** Generic function to execute a SELECT query */
    protected fun <T> executeQuery(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T {
        return conn.prepareStatement(sql).use { pstmt ->
            setParams(pstmt, params)
            pstmt.executeQuery().use { rs -> mapper(rs) }
        }
    }

    /** Generic function to execute a SELECT query with retries */
    protected fun <T> executeQueryWithRetry(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T {
        return retryTransaction { executeQuery(sql, *params, mapper = mapper) }
    }

    /** Generic function to execute an UPDATE or INSERT query */
    protected fun executeUpdate(sql: String, vararg params: Any?): Boolean {
        return conn.prepareStatement(sql).use { pstmt ->
            setParams(pstmt, params)
            pstmt.executeUpdate() > 0
        }
    }

    /** Generic function to execute an INSERT or UPDATE query with retries */
    protected fun executeUpdateWithRetry(sql: String, vararg params: Any?): Boolean {
        return retryTransaction { executeUpdate(sql, *params) }
    }

    /** Generic function to execute a batch INSERT or UPDATE query */
    protected fun executeBatch(sql: String, paramsList: List<Array<Any>>): Boolean {
        return conn.prepareStatement(sql).use { pstmt ->
            for (params in paramsList) {
                setParams(pstmt, params)
                pstmt.addBatch()
            }
            pstmt.executeBatch().sum() > 0
        }
    }

    /** Generic function to execute a batch INSERT or UPDATE query with retries */
    protected fun executeBatchWithRetry(sql: String, paramsList: List<Array<Any>>): Boolean {
        return retryTransaction { executeBatch(sql, paramsList) }
    }

    /** Helper function to fetch last inserted ID */
    protected fun getLastInsertId(): Int {
        val sql = "SELECT last_insert_rowid()"
        return executeQuery(sql) { rs -> if (rs.next()) rs.getInt(1) else -1 }
    }

    /** Extension function to handle nullable Int values */
    protected fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    protected fun getLastInsertStringId(): String {
        val sqlLastId = "SELECT last_insert_rowid()"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sqlLastId).use { rs ->
                if (rs.next()) {
                    return rs.getString(1)
                }
            }
        }
        return ""
    }

    protected fun <T> retryTransaction(retries: Int = 30, action: () -> T): T {
        repeat(retries) { attempt ->
            try {
                return action()
            } catch (e: SQLException) {
                    if (e.message!!.contains("SQLITE_BUSY"))
                        log.debug("Database is busy, retrying... (attempt ${attempt + 1})")
                    else throw RuntimeException("Retry transaction fail: ${e.message}")
                    Thread.sleep(retries * 5L)
            }
        }
        throw SQLException("Failed after $retries attempts due to database being busy.")
    }

    protected inline fun <reified T : Number> getIdResult(rs: ResultSet): T? {
        return if (rs.next()) {
            when (T::class) {
                Int::class -> rs.getInt("id") as T
                Long::class -> rs.getLong("id") as T
                else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
            }
        } else {
            null
        }
    }

    companion object {
        fun compress(input: String): String {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { gzip ->
                gzip.write(input.toByteArray(Charsets.UTF_8))
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray())
        }

        fun decompress(compressed: String): String {
            val bytes = Base64.getDecoder().decode(compressed)
            ByteArrayInputStream(bytes).use { bis ->
                GZIPInputStream(bis).use { gzip ->
                    return gzip.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        }
    }
}