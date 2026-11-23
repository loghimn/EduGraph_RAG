package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Durable delivery queue, committed atomically with removal of course enrollments. */
@Entity
@Table(name = "course_privacy_mail", indexes = @Index(name = "idx_privacy_mail_due", columnList = "nextAttemptAt"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CoursePrivacyMail {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String email;
    @Column(nullable = false) private String courseName;
    @Column(nullable = false) private LocalDateTime nextAttemptAt;
    private int attempts;
}
