package com.example.expensetracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.expensetracker.data.model.Expense
import com.example.expensetracker.data.model.SecurityLabEvent

@Database(
    entities = [Expense::class, SecurityLabEvent::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun expenseDao(): ExpenseDao
    abstract fun securityLabEventDao(): SecurityLabEventDao

    companion object {
        const val VERSION = 3
        const val NAME = "daily_spend.db"

        /**
         * Version 2 added the periodic generator's own record table. Version 3 removes it again when
         * the lab moves from synthetic call-log fixtures to the real device call log: the table held
         * dummy fixtures only, and the device path is send-only (Room + JSONL transfer audit, no
         * call-log table). Expenses and the audit trail are not touched.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `lab_call_log_records`")
            }
        }

        /**
         * Version 1 already had expenses and the audit table, and version 3 is the same schema as
         * version 1. The direct 1 -> 3 path therefore only needs to make sure a table that never
         * belonged in v1 is absent; without this entry Room refuses to open an upgraded v1 database.
         */
        private val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `lab_call_log_records`")
            }
        }

        /**
         * Bump [VERSION] together with an entry here and a committed JSON schema under app/schemas;
         * destructive migration is deliberately disabled so a forensic database is never silently
         * rebuilt. Both shipped predecessors (1 and 2) have a direct path to the current version.
         */
        private val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_3, MIGRATION_2_3)

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
