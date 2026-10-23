package fu.coreservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetConfigTest {
    @Test void saturatedExecutorRejectsInsteadOfRunningOnRequestThread() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var context = new AnnotationConfigApplicationContext(PasswordResetConfig.class)) {
            var executor = context.getBean("passwordResetExecutor", ThreadPoolTaskExecutor.class);
            try {
                for (int i = 0; i < 2; i++) executor.execute(() -> {
                    started.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 100; i++) executor.execute(() -> {});
                assertThatThrownBy(() -> executor.execute(() -> {
                    throw new AssertionError("Must never run on caller thread");
                })).isInstanceOf(TaskRejectedException.class);
            } finally { release.countDown(); }
        }
    }
}
