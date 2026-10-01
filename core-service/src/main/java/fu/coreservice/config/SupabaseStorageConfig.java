package fu.coreservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@ConfigurationProperties(prefix = "app.supabase")
@Getter
@Setter
public class SupabaseStorageConfig {

    private String url;
    private String secretKey;
    private String bucketName;

    @Bean
    public RestTemplate supabaseRestTemplate() {
        return new RestTemplate();
    }

    public String getUploadUrl(String filePath) {
        return url
                + "/storage/v1/object/"
                + bucketName
                + "/"
                + filePath;
    }

}
