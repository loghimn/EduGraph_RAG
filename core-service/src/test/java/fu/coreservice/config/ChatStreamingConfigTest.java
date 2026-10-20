package fu.coreservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ChatStreamingConfigTest {
    @Test
    void mvcUsesManagedVirtualThreadExecutor() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(ChatStreamingConfig.class)) {
            var configurer = new AsyncSupportConfigurer();
            context.getBean(ChatStreamingConfig.class).configureAsyncSupport(configurer);
            var executor = context.getBean("chatStreamingExecutor", SimpleAsyncTaskExecutor.class);
            assertSame(executor, ReflectionTestUtils.getField(configurer, "taskExecutor"));
            CompletableFuture<Boolean> virtual = new CompletableFuture<>();
            executor.execute(() -> virtual.complete(Thread.currentThread().isVirtual()
                    && Thread.currentThread().getName().startsWith("chat-stream-")));
            assertTrue(virtual.get(5, TimeUnit.SECONDS));
        }
    }
}
