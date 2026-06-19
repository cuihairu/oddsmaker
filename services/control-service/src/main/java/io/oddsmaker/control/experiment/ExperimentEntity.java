package io.oddsmaker.control.experiment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "experiments")
public class ExperimentEntity {
    @Id
    public String id;

    @Column(name = "game_id")
    public String gameId;

    @Column(name = "environment_id")
    public String environmentId;

    public String name;
    public String status;
    public String salt;
    @Lob
    @Column(name = "config_json", columnDefinition = "TEXT")
    public String configJson;
    public Instant createdAt;
    public Instant updatedAt;
}
