package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "learning_path_goals", uniqueConstraints = @UniqueConstraint(
        name = "uk_plan_goal_concept", columnNames = {"plan_id", "neo4j_concept_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningPathGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "goal_id")
    private Long goalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private LearningPathPlan plan;

    // Logical reference to Neo4j, not a relational foreign key.
    @Column(name = "neo4j_concept_id", nullable = false, length = 100)
    private String neo4jConceptId;
}
