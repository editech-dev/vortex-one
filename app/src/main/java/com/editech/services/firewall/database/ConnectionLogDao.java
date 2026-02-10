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
}
