package fu.coreservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ChatStreamingConfig implements WebMvcConfigurer {
    @Bean(destroyMethod = "close")
    public SimpleAsyncTaskExecutor chatStreamingExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("chat-stream-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(5_000);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // MVC StreamingResponseBody uses this executor; Boot applies the timeout
        // from spring.mvc.async.request-timeout in application.yml.
        configurer.setTaskExecutor(chatStreamingExecutor());
    }
}
