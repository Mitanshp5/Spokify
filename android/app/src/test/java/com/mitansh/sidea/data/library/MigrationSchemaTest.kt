package com.mitansh.sidea.data.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Guards the Room schema against the migration drifting away from it.
 *
 * Room validates the migrated table against the generated schema when the database opens, and
 * refuses to run the app when they differ. That check only happens on a device, so a mismatch can
 * reach a release unnoticed. This compares the migration's statements with the exported schema
 * instead, which is cheap enough to run as a unit test.
 */
class MigrationSchemaTest {

    @Test
    fun `the listening stats migration matches the exported room schema`() {
        val schema = exportedSchema()
        val statements = recordedStatements()

        assertTrue(
            "the migration must create the listening_stats table",
            statements.any { it.contains("CREATE TABLE IF NOT EXISTS `listening_stats`") },
        )
        assertTrue(
            "the created table must match Room's generated columns",
            statements.any { it.contains(schema.createSql.replace("\${TABLE_NAME}", "listening_stats")) },
        )
        for (index in schema.indices) {
            assertTrue(
                "the migration must create Room's index $index",
                statements.any { it.contains("`$index`") },
            )
        }
    }

    @Test
    fun `the exported schema is present for the current database version`() {
        val schema = exportedSchema()
        assertTrue(
            "expected the listening_stats columns",
            schema.createSql.contains("`artistKey`") && schema.createSql.contains("`playCount`"),
        )
        assertTrue(
            "expected the playCount and artistKey indexes",
            schema.indices.containsAll(
                listOf("index_listening_stats_playCount", "index_listening_stats_artistKey"),
            ),
        )
    }

    /** Runs the migration against a recording stand-in and returns the SQL it issued. */
    private fun recordedStatements(): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    statements += args?.getOrNull(0) as? String ?: ""
                    null
                }
                "query" -> null
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as androidx.sqlite.db.SupportSQLiteDatabase
        SideADatabase.MIGRATION_2_3.migrate(database)
        return statements
    }

    private data class ExportedSchema(val createSql: String, val indices: List<String>)

    /**
     * Reads the exported schema.
     *
     * Plain text scanning is used on purpose: the Android JSON classes are not usable in a plain
     * unit test, and the exported file is machine-written and stable.
     */
    private fun exportedSchema(): ExportedSchema {
        val file = locateExportedSchema()
            ?: throw AssertionError("the exported schema for the current database version was not found")
        val text = file.readText()
        val section = text.substringAfter("\"tableName\": \"listening_stats\"")
        val createSql = Regex("\"createSql\": \"(CREATE TABLE[^\"]*)\"").find(section)?.groupValues?.get(1)
            ?: throw AssertionError("the exported schema has no createSql for listening_stats")
        val indices = Regex("\"name\": \"(index_listening_stats_[A-Za-z]+)\"").findAll(text)
            .map { it.groupValues[1] }.toList()
        return ExportedSchema(createSql, indices)
    }

    /** The schema is exported under the module's schemas directory, relative to the test run. */
    private fun locateExportedSchema(): File? {
        val name = "com.mitansh.sidea.data.library.SideADatabase/3.json"
        val candidates = listOf(
            File("src/main/schemas/$name"),
            File("app/src/main/schemas/$name"),
            File("../app/src/main/schemas/$name"),
            File("schemas/$name"),
        )
        return candidates.firstOrNull { it.isFile }
    }
}
