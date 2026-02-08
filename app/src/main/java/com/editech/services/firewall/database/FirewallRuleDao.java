package com.editech.services.firewall.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface FirewallRuleDao {
    // === App State Methods ===
    @Query("INSERT OR REPLACE INTO firewall_states (packageName, state) VALUES (:packageName, :state)")
    void setAppState(String packageName, String state);

    @Query("SELECT * FROM firewall_states")
    List<FirewallAppStateEntity> getAllAppStates();

    @Query("SELECT state FROM firewall_states WHERE packageName = :packageName")
    String getAppState(String packageName);

    // === Rule Methods ===
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(FirewallRuleEntity rule);

    @Query("DELETE FROM firewall_rules WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName")
    List<FirewallRuleEntity> getRulesForPackage(String packageName);
}
