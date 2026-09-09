package com.ebookreader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ebookreader.data.local.dao.AnnotationDao
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookKeywordDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.ChatDao
import com.ebookreader.data.local.dao.TagDao
import com.ebookreader.data.local.dao.BookChunkDao
import com.ebookreader.data.local.dao.BookDecompositionDao
import com.ebookreader.data.local.dao.ChunkEmbeddingDao
import com.ebookreader.data.local.dao.DailyBookReadingDao
import com.ebookreader.data.local.dao.DailyReadingSessionDao
import com.ebookreader.data.local.entity.AnnotationEntity
import com.ebookreader.data.local.entity.BookChunkEntity
import com.ebookreader.data.local.entity.BookDecompositionEntity
import com.ebookreader.data.local.entity.ChunkEmbeddingEntity
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookKeywordEntity
import com.ebookreader.data.local.entity.BookRelationCrossRef
import com.ebookreader.data.local.entity.BookTagCrossRef
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.ChapterEntity
import com.ebookreader.data.local.entity.ChatMessageEntity
import com.ebookreader.data.local.entity.ConversationEntity
import com.ebookreader.data.local.entity.DailyBookReadingEntity
import com.ebookreader.data.local.entity.DailyReadingSessionEntity
import com.ebookreader.data.local.entity.TagEntity
import com.ebookreader.data.local.entity.TagGroupEntity

@Database(
    entities = [
        BookEntity::class,
        TagEntity::class,
        BookTagCrossRef::class,
        BookRelationCrossRef::class,
        TagGroupEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        ChapterEntity::class,
        ConversationEntity::class,
        ChatMessageEntity::class,
        BookChunkEntity::class,
        ChunkEmbeddingEntity::class,
        DailyReadingSessionEntity::class,
        DailyBookReadingEntity::class,
        BookDecompositionEntity::class,
        BookKeywordEntity::class,
    ],
    version = 22,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookKeywordDao(): BookKeywordDao
    abstract fun tagDao(): TagDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun chatDao(): ChatDao
    abstract fun bookChunkDao(): BookChunkDao
    abstract fun chunkEmbeddingDao(): ChunkEmbeddingDao
    abstract fun bookDecompositionDao(): BookDecompositionDao
    abstract fun dailyReadingSessionDao(): DailyReadingSessionDao
    abstract fun dailyBookReadingDao(): DailyBookReadingDao

    /** 压缩数据库文件，回收删除数据后未释放的空间。需在后台线程调用。 */
    fun vacuum() {
        openHelper.writableDatabase.execSQL("VACUUM")
    }

    /** 执行 WAL 检查点，把已提交事务合并回主库文件，确保 .db 自包含（导出备份前调用）。 */
    fun checkpoint() {
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        createdTimestamp INTEGER NOT NULL DEFAULT 0,
                        updatedTimestamp INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_bookId ON conversations(bookId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_conversationId ON chat_messages(conversationId)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        chapterTitle TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL,
                        charOffset INTEGER NOT NULL,
                        fileModified INTEGER NOT NULL,
                        FOREIGN KEY (bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_chunks_bookId ON book_chunks(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_chunks_bookId_chunkIndex ON book_chunks(bookId, chunkIndex)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_chunks ADD COLUMN eventSummary TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_reading_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        seconds INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_daily_reading_sessions_date ON daily_reading_sessions(date)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN originalFilePath TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS voice_profiles")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_book_reading (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        bookId INTEGER NOT NULL,
                        seconds INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_daily_book_reading_date_bookId ON daily_book_reading(date, bookId)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tag_groups ADD COLUMN query TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_decomposition (
                        bookId INTEGER PRIMARY KEY NOT NULL,
                        bookType TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        outlineJson TEXT NOT NULL,
                        chapterSummariesJson TEXT NOT NULL,
                        bookSummary TEXT NOT NULL,
                        status TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN charactersJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN timelineJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN quotesJson TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN characterBiosJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN worldSettingJson TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN chapterOverviewJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE book_decomposition ADD COLUMN extendedReadingJson TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 上一次构建曾用带 DEFAULT 的 schema 建表，触发 Room 校验崩溃并可能残留半成品表；
                // 此处先删后建，确保按 Room 期望的精确 schema 重建（全新功能表，无历史数据可丢失）。
                db.execSQL("DROP TABLE IF EXISTS annotations")
                db.execSQL(
                    """
                    CREATE TABLE annotations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        locatorJson TEXT NOT NULL,
                        selectedText TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        style TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        createdTimestamp INTEGER NOT NULL,
                        FOREIGN KEY (bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations(bookId)")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN totalCharacters INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chunk_embeddings (
                        chunkId INTEGER NOT NULL,
                        bookId INTEGER NOT NULL,
                        dim INTEGER NOT NULL,
                        vector BLOB NOT NULL,
                        PRIMARY KEY (chunkId),
                        FOREIGN KEY (chunkId) REFERENCES book_chunks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chunk_embeddings_bookId ON chunk_embeddings(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chunk_embeddings_chunkId ON chunk_embeddings(chunkId)")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_keywords (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        keyword TEXT NOT NULL,
                        weight REAL NOT NULL,
                        rank INTEGER NOT NULL,
                        generatedTimestamp INTEGER NOT NULL,
                        FOREIGN KEY (bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_keywords_bookId ON book_keywords(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_book_keywords_bookId_keyword ON book_keywords(bookId, keyword)")
            }
        }

        // 降级迁移：早期版本短暂引入过「术语库」表（v19），现已回退到 v18。
        // 已升级到 v19 的旧库在降级时删除该表，避免 Room 因缺少 19→18 迁移而崩溃。
        val MIGRATION_19_18 = object : Migration(19, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS glossary_entries")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ebook_reader.db",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_19_18)
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                    .build().also { INSTANCE = it }
            }
        }

        /** 关闭并置空单例。用于「导入备份」在覆盖数据库文件前释放句柄；下次 getInstance 重新打开。 */
        fun closeInstance() {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } catch (_: Exception) {
                }
                INSTANCE = null
            }
        }
    }
}
