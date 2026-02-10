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
    public String method;
    public String path;

    public ConnectionLogEntity(String packageName, long timestamp, String destinationIp, int destinationPort,
            String protocol, boolean wasBlocked, String hostname, String status, String failureReason,
            String method, String path) {
        this.packageName = packageName;
        this.timestamp = timestamp;
        this.destinationIp = destinationIp;
        this.destinationPort = destinationPort;
        this.protocol = protocol;
        this.wasBlocked = wasBlocked;
        this.hostname = hostname;
        this.status = status;
        this.failureReason = failureReason;
        this.method = method;
        this.path = path;
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
                method,
                path,
                0 // bytesTransferred default
        );
    }
}
