package io.pit.control.experiment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "experiments")
public class ExperimentEntity {
    @Id
    public String id;            // exp key (unique per app)
    public String tenantId;      // organization_id; multi-tenant isolation key
    public String appId;         // game_id + environment
    public String name;
    public String status;        // draft|running|paused
    public String salt;          // for hashing assignment
    @Lob
    @Column(columnDefinition = "CLOB")
    public String configJson;    // JSON of variants/weights/targeting
    public Instant createdAt;
    public Instant updatedAt;
}
