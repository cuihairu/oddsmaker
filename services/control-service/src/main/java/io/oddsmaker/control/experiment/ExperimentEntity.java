package io.oddsmaker.control.experiment;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "experiments")
public class ExperimentEntity {

    public enum ExperimentStatus {
        DRAFT, LIVE, PAUSED, ENDED
    }

    @Id
    public String id;

    @Column(name = "game_id")
    public String gameId;

    @Column(name = "environment_id")
    public String environmentId;

    public String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    public ExperimentStatus status = ExperimentStatus.DRAFT;

    public String salt;

    @Lob
    @Column(name = "config_json", columnDefinition = "TEXT")
    public String configJson;

    // B8 formalization fields
    @Column(name = "audience_segment_id", length = 64)
    public String audienceSegmentId;

    @Lob
    @Column(name = "variants_json", columnDefinition = "TEXT")
    public String variantsJson;

    @Lob
    @Column(name = "allocation_info", columnDefinition = "TEXT")
    public String allocationInfo;

    @Lob
    @Column(name = "guardrails_json", columnDefinition = "TEXT")
    public String guardrailsJson;

    @Lob
    @Column(name = "decision_json", columnDefinition = "TEXT")
    public String decisionJson;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}