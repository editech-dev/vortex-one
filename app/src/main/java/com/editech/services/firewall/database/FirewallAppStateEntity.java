package com.editech.services.firewall.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "firewall_states")
public class FirewallAppStateEntity {
    @PrimaryKey
    @NonNull
    public String packageName;

    public String state; // DISABLED, MONITORING, BLOCKING_ALL, BLOCKING_PORTS

    public FirewallAppStateEntity(@NonNull String packageName, String state) {
        this.packageName = packageName;
        this.state = state;
    }
}
