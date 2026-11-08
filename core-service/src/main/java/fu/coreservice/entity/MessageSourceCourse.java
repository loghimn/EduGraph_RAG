package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "message_source_courses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_message_source_courses_message_course",
                columnNames = {"message_id", "course_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageSourceCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_source_course_id")
    private Long messageSourceCourseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Column(name = "course_id", nullable = false)
    private Long courseId;
}
