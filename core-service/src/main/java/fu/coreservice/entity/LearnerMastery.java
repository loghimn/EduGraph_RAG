package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "learner_masteries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_learner_course_concept",
                        columnNames = {"learner_id", "course_id", "neo4j_concept_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearnerMastery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mastery_id")
    private Long masteryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "learner_id", nullable = false)
    private User learner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "neo4j_concept_id", nullable = false, length = 100)
    private String neo4jConceptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mastery_status", nullable = false)
    @Builder.Default
    private MasteryStatus masteryStatus = MasteryStatus.NOT_STARTED;

    @Column(name = "is_self_rated", nullable = false)
    @Builder.Default
    private Boolean isSelfRated = true;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
