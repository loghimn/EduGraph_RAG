package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
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
    private LearningPathPlan plan;

    @Column(name = "neo4j_concept_id", nullable = false, length = 100)
    private String neo4jConceptId;

    @Column(name = "concept_label", nullable = false, length = 255)
    private String conceptLabel;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private PathStepStatus status = PathStepStatus.PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
