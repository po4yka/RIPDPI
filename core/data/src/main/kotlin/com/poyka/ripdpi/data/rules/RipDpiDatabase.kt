package com.poyka.ripdpi.data.rules

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RuleEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RuleTypeConverters::class)
abstract class RipDpiDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    /**
     * Seeds two default rules on first database creation.
     * Users can delete either seed rule at any time.
     */
    internal object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // bypass-loopback: 127.0.0.1/8 and ::1/128
            db.execSQL(
                """
                INSERT OR IGNORE INTO routing_rules
                    (id, name, userOrder, enabled, domains, ipCidrs, ports, sourcePorts, network, processName, packages, outboundTag)
                VALUES
                    (1, 'Bypass loopback', 0, 1, '', '127.0.0.0/8\n::1/128', '', '', 'BOTH', '', '', '-1:0')
                """.trimIndent(),
            )
            // bypass-LAN: RFC-1918 + link-local ranges
            db.execSQL(
                """
                INSERT OR IGNORE INTO routing_rules
                    (id, name, userOrder, enabled, domains, ipCidrs, ports, sourcePorts, network, processName, packages, outboundTag)
                VALUES
                    (2, 'Bypass LAN', 1, 1, '', '192.168.0.0/16\n10.0.0.0/8\n172.16.0.0/12\n169.254.0.0/16\nfc00::/7', '', '', 'BOTH', '', '', '-1:0')
                """.trimIndent(),
            )
        }
    }
}
