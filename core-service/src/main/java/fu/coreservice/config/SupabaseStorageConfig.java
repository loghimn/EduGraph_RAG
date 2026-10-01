package fu.coreservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.supabase")
@Getter
@Setter
public class SupabaseStorageConfig {

    private String url;
    private String secretKey;
    private String bucketName;

    @Bean
    public RestTemplate supabaseRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    public String getUploadUrl(String filePath) {
        return url
                + "/storage/v1/object/"
                + bucketName
                + "/"
                + filePath;
    }

}
