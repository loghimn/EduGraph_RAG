package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

@Entity
@Check(constraints = "stage_level >= 0")
@Table(
        name = "learning_path_steps",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_plan_step_order",
                        columnNames = {"plan_id", "step_order"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningPathStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_id")
    private Long stepId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private LearningPathPlan plan;

    @Column(name = "neo4j_concept_id", nullable = false, length = 100)
    private String neo4jConceptId;

    @Column(name = "concept_label", nullable = false, length = 255)
    private String conceptLabel;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    // Zero-based generation; null for legacy steps with no recorded stage.
    @Column(name = "stage_level")
    private Integer stageLevel;

    // Null in GOAL mode, true/false in DOCUMENT and COURSE modes.
    @Column(name = "outside_document")
    private Boolean outsideDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private PathStepStatus status = PathStepStatus.PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
