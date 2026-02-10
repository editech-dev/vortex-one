package com.editech.services.firewall.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = { FirewallRuleEntity.class, ConnectionLogEntity.class,
        FirewallAppStateEntity.class }, version = 5, exportSchema = false)
public abstract class FirewallDatabase extends RoomDatabase {
    public abstract FirewallRuleDao ruleDao();

    public abstract ConnectionLogDao logDao();

    private static volatile FirewallDatabase INSTANCE;

    public static FirewallDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (FirewallDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            FirewallDatabase.class, "firewall_database")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
