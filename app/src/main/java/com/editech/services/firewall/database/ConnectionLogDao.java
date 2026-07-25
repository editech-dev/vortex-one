package com.editech.services.firewall.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ConnectionLogDao {
    @Insert
    void insert(ConnectionLogEntity log);

    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT :limit")
    List<ConnectionLogEntity> getRecentLogs(int limit);

    @Query("SELECT * FROM connection_logs WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    List<ConnectionLogEntity> getLogsForApp(String packageName, int limit);

    @Query("DELETE FROM connection_logs WHERE timestamp < :cutoffTime")
    void deleteOldLogs(long cutoffTime);

    @Query("DELETE FROM connection_logs WHERE id NOT IN (SELECT id FROM connection_logs ORDER BY timestamp DESC LIMIT :maxKeep)")
    void trimLogsToMax(int maxKeep);

    @Query("SELECT DISTINCT destinationPort, protocol FROM connection_logs WHERE packageName = :packageName AND destinationPort != 0")
    List<PortInfo> getDistinctPorts(String packageName);

    @Query("SELECT DISTINCT path FROM connection_logs WHERE packageName = :packageName AND path IS NOT NULL AND path != ''")
    List<String> getDistinctEndpoints(String packageName);

    public static class PortInfo {
        public int destinationPort;
        public String protocol;
    }

    @Query("DELETE FROM connection_logs")
    void clearAll();

    @Query("SELECT * FROM connection_logs WHERE packageName = :packageName AND failureReason LIKE 'THREAT:%' ORDER BY timestamp DESC LIMIT :limit")
    List<ConnectionLogEntity> getThreatLogs(String packageName, int limit);

    // ── Tor-specific queries ─────────────────────────────────────────────────

    @Query("SELECT * FROM connection_logs " +
           "WHERE packageName = :packageName AND protocol LIKE 'TOR%' " +
           "ORDER BY timestamp DESC LIMIT :limit")
    List<ConnectionLogEntity> getTorLogs(String packageName, int limit);

    @Query("SELECT COUNT(*) FROM connection_logs " +
           "WHERE packageName = :packageName AND protocol LIKE 'TOR%' " +
           "AND status = 'ESTABLISHED'")
    int getTorSuccessCount(String packageName);

    @Query("SELECT COUNT(*) FROM connection_logs " +
           "WHERE packageName = :packageName AND protocol LIKE 'TOR%' " +
           "AND status != 'ESTABLISHED'")
    int getTorFailureCount(String packageName);
}
