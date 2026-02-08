package com.editech.services.firewall.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import com.editech.services.firewall.ConnectionLog;

@Entity(tableName = "connection_logs")
public class ConnectionLogEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String packageName;
    public long timestamp;
    public String destinationIp;
    public int destinationPort;
    public String protocol; // TCP/UDP
    public boolean wasBlocked;
    public String hostname; // DNS hostname if available
    public String status; // BLOCKED, ESTABLISHED, FAILED
    public String failureReason;

    public ConnectionLogEntity(String packageName, long timestamp, String destinationIp, int destinationPort,
            String protocol, boolean wasBlocked, String hostname, String status, String failureReason) {
        this.packageName = packageName;
        this.timestamp = timestamp;
        this.destinationIp = destinationIp;
        this.destinationPort = destinationPort;
        this.protocol = protocol;
        this.wasBlocked = wasBlocked;
        this.hostname = hostname;
        this.status = status;
        this.failureReason = failureReason;
    }

    public ConnectionLog toModel() {
        return new ConnectionLog(
                id,
                packageName,
                destinationIp,
                destinationPort,
                hostname,
                protocol,
                timestamp,
                wasBlocked,
                status,
                failureReason,
                0 // bytesTransferred default
        );
    }
}
