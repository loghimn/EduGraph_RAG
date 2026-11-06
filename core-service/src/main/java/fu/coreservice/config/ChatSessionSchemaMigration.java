package fu.coreservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("ALTER TABLE chat_sessions ALTER COLUMN course_id DROP NOT NULL");
            log.info("Dropped NOT NULL constraint on chat_sessions.course_id");
        } catch (Exception e) {
            // Already nullable, unsupported database, or fresh schema created by Hibernate.
            log.debug("chat_sessions.course_id NOT NULL migration skipped: {}", e.getMessage());
        }
    }
}