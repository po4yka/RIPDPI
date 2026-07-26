package com.poyka.ripdpi.data.rules

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity

@Database(
    entities = [RuleEntity::class, AwgProfileEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(RuleTypeConverters::class)
abstract class RipDpiDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun awgProfileDao(): AwgProfileDao

    /**
     * Seeds two default rules on first database creation.
     * Users can delete either seed rule at any time.
     */
    internal object SeedCallback : Callback() {
        private const val LOOPBACK_CIDRS = "127.0.0.0/8\n::1/128"
        private const val LEGACY_LOOPBACK_CIDRS = "127.0.0.0/8\\n::1/128"
        private const val LAN_CIDRS = "192.168.0.0/16\n10.0.0.0/8\n172.16.0.0/12\n169.254.0.0/16\nfc00::/7"
        private const val LEGACY_LAN_CIDRS =
            "192.168.0.0/16\\n10.0.0.0/8\\n172.16.0.0/12\\n169.254.0.0/16\\nfc00::/7"

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // bypass-loopback: 127.0.0.1/8 and ::1/128
            db.execSQL(
                """
                INSERT OR IGNORE INTO routing_rules
                    (id, name, userOrder, enabled, domains, ipCidrs, ports, sourcePorts, network, processName, packages, outboundTag)
                VALUES
                    (1, 'Bypass loopback', 0, 1, '', ?, '', '', 'BOTH', '', '', '-1:0')
                """.trimIndent(),
                arrayOf(LOOPBACK_CIDRS),
            )
            // bypass-LAN: RFC-1918 + link-local ranges
            db.execSQL(
                """
                INSERT OR IGNORE INTO routing_rules
                    (id, name, userOrder, enabled, domains, ipCidrs, ports, sourcePorts, network, processName, packages, outboundTag)
                VALUES
                    (2, 'Bypass LAN', 1, 1, '', ?, '', '', 'BOTH', '', '', '-1:0')
                """.trimIndent(),
                arrayOf(LAN_CIDRS),
            )
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            repairLegacySeed(
                db,
                id = 1,
                name = "Bypass loopback",
                legacy = LEGACY_LOOPBACK_CIDRS,
                fixed = LOOPBACK_CIDRS,
            )
            repairLegacySeed(
                db,
                id = 2,
                name = "Bypass LAN",
                legacy = LEGACY_LAN_CIDRS,
                fixed = LAN_CIDRS,
            )
        }

        private fun repairLegacySeed(
            db: SupportSQLiteDatabase,
            id: Int,
            name: String,
            legacy: String,
            fixed: String,
        ) {
            db.execSQL(
                "UPDATE routing_rules SET ipCidrs = ? WHERE id = ? AND name = ? AND ipCidrs = ?",
                arrayOf<Any>(fixed, id, name, legacy),
            )
        }
    }
}
