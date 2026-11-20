package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One resolved target per completed answer; no question text or personal data. */
@Entity
@Table(name = "concept_queries", uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "concept_id"}),
        indexes = @Index(name = "idx_concept_queries_course", columnList = "course_id, concept_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ConceptQuery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "message_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatMessage message;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "course_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Course course;
    @Column(name = "concept_id", nullable = false, length = 200)
    private String conceptId;
    @Column(nullable = false, length = 255)
    private String name;
}
