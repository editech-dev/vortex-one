package com.editech.services.firewall.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import com.editech.services.firewall.FirewallRule;
import com.editech.services.firewall.RuleType;
import com.editech.services.firewall.Protocol;

@Entity(tableName = "firewall_rules")
public class FirewallRuleEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String packageName;
    public String ruleType; // BLOCK_ALL, BLOCK_PORT, ALLOW_ONLY_PORT
    public int port;
    public String protocol; // TCP, UDP, BOTH
    public boolean enabled;
    public long createdAt;
    public String endpoint;

    public FirewallRuleEntity(String packageName, String ruleType, int port, String protocol, boolean enabled,
            long createdAt, String endpoint) {
        this.packageName = packageName;
        this.ruleType = ruleType;
        this.port = port;
        this.protocol = protocol;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.endpoint = endpoint;
    }

    public FirewallRule toModel() {
        return new FirewallRule(
                id,
                packageName,
                RuleType.valueOf(ruleType),
                port == -1 ? null : port, // Assuming -1 for null/all ports if needed, or handle nullability logic
                endpoint,
                Protocol.valueOf(protocol),
                enabled,
                createdAt);
    }
}
