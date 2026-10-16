package com.assassins377.remoteplayer.library

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One indexed video file (Room row). search* columns are normalized (lowercase, ё→е) for FTS. */
@Entity(
    tableName = "entries",
    indices = [Index(value = ["serverId", "path"], unique = true), Index("serverId"), Index("titleSort"), Index("year"), Index("addedAt"), Index("seriesKey")],
)
data class EntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val path: String,
    val name: String,
    val title: String,
    val titleSort: String,
    val year: Int?,
    val size: Long,
    val modified: Long,
    val addedAt: Long,
    val scanId: Long,
    val searchName: String,
    val searchTitle: String,
    /** Normalized series title when the file is an episode (SxxEyy, 1x02, «1 сезон 2 серия»), else null. */
    val seriesKey: String? = null,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class SeriesRow(val seriesKey: String, val seriesTitle: String, val episodes: Int, val seasons: Int, val addedAt: Long, val samplePath: String, val sampleServer: String)

@Fts4(contentEntity = EntryRow::class)
@Entity(tableName = "entries_fts")
data class EntryFts(val searchName: String, val searchTitle: String)

data class KnownRow(val id: Long, val path: String, val addedAt: Long)

@Dao
interface LibraryDao {
    @Upsert suspend fun upsert(rows: List<EntryRow>)

    @Query("SELECT id, path, addedAt FROM entries WHERE serverId = :serverId")
    suspend fun known(serverId: String): List<KnownRow>

    @Query("DELETE FROM entries WHERE serverId = :serverId AND scanId != :scanId")
    suspend fun deleteStale(serverId: String, scanId: Long): Int

    @Query("DELETE FROM entries WHERE serverId NOT IN (:live)")
    suspend fun deleteServersExcept(live: List<String>): Int

    @Query("DELETE FROM entries") suspend fun clear()

    @Query("SELECT * FROM entries WHERE serverId = :serverId AND path = :path LIMIT 1")
    suspend fun find(serverId: String, path: String): EntryRow?

    @Query("SELECT * FROM entries WHERE seriesKey = :key AND serverId IN (:live) ORDER BY season, episode, path")
    fun observeEpisodes(key: String, live: List<String>): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE seriesKey = :key AND serverId IN (:live) ORDER BY season, episode, path")
    suspend fun episodes(key: String, live: List<String>): List<EntryRow>

    @RawQuery(observedEntities = [EntryRow::class])
    fun observeSeries(q: SupportSQLiteQuery): Flow<List<SeriesRow>>

    @RawQuery suspend fun series(q: SupportSQLiteQuery): List<SeriesRow>

    @Query("SELECT COUNT(*) FROM entries") suspend fun count(): Int

    @RawQuery(observedEntities = [EntryRow::class])
    fun observe(q: SupportSQLiteQuery): Flow<List<EntryRow>>

    @RawQuery suspend fun query(q: SupportSQLiteQuery): List<EntryRow>

    @RawQuery(observedEntities = [EntryRow::class])
    fun observeCount(q: SupportSQLiteQuery): Flow<Int>

    @RawQuery suspend fun queryCount(q: SupportSQLiteQuery): Int
}

@Database(entities = [EntryRow::class, EntryFts::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(ctx: Context): LibraryDatabase = Room.databaseBuilder(ctx, LibraryDatabase::class.java, "library.db")
            .fallbackToDestructiveMigration(true).build()
        fun inMemory(ctx: Context): LibraryDatabase = Room.inMemoryDatabaseBuilder(ctx, LibraryDatabase::class.java).build()
    }
}

/** Builds the SQL for search + server filter + sort + LIMIT/OFFSET; pure for tests. */
object LibrarySql {
    fun normalize(s: String) = s.lowercase().replace('ё', 'е').replace('.', ' ').replace('_', ' ')

    /** FTS MATCH expression: every word as a prefix ("брат* 1997*"); null when the query has no words. */
    fun match(query: String): String? {
        val words = normalize(query).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }

    private fun where(query: String, servers: Collection<String>?, live: Collection<String>, args: MutableList<Any>, mode: LibraryMode = LibraryMode.ALL): String {
        val c = mutableListOf<String>()
        if (mode == LibraryMode.MOVIES) c += "seriesKey IS NULL"
        c += "serverId IN (${live.joinToString(",") { "?" }})"; args.addAll(live)
        if (servers != null) { c += "serverId IN (${servers.joinToString(",") { "?" }})"; args.addAll(servers) }
        match(query)?.let { c += "id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)"; args += it }
        return c.joinToString(" AND ")
    }

    fun list(query: String, servers: Collection<String>?, sort: LibrarySort, live: Collection<String>, limit: Int, offset: Int = 0, mode: LibraryMode = LibraryMode.ALL): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val order = when (sort) {
            LibrarySort.TITLE -> "titleSort, path"
            LibrarySort.YEAR -> "IFNULL(year, 0) DESC, titleSort, path"
            LibrarySort.ADDED -> "addedAt DESC, titleSort, path"
        }
        val sql = "SELECT * FROM entries WHERE ${where(query, servers, live, args, mode)} ORDER BY $order LIMIT $limit OFFSET $offset"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun count(query: String, servers: Collection<String>?, live: Collection<String>, mode: LibraryMode = LibraryMode.ALL): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM entries WHERE ${where(query, servers, live, args, mode)}", args.toTypedArray())
    }

    /** Series grouped by seriesKey (search/filter apply to episodes). */
    fun series(query: String, servers: Collection<String>?, sort: LibrarySort, live: Collection<String>, limit: Int): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val order = when (sort) { LibrarySort.ADDED -> "addedAt DESC, seriesTitle"; else -> "LOWER(seriesTitle)" }
        val sql = "SELECT seriesKey, MIN(seriesTitle) AS seriesTitle, COUNT(*) AS episodes, COUNT(DISTINCT season) AS seasons, " +
            "MAX(addedAt) AS addedAt, MIN(path) AS samplePath, MIN(serverId) AS sampleServer FROM entries " +
            "WHERE seriesKey IS NOT NULL AND ${where(query, servers, live, args)} GROUP BY seriesKey ORDER BY $order LIMIT $limit"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
