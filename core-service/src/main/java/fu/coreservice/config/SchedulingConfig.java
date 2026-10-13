package fu.coreservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật @Scheduled cho các poller nội bộ (đồng bộ trạng thái xử lý tài liệu từ AI core).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
