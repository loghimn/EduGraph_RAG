package fu.gatewayservice;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayServiceApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

    /**
     * Nạp file {@code env} ở thư mục module (working directory) thành system properties.
     * Biến môi trường thật (IDE inject qua envFilePaths, CI, shell...) luôn được ưu tiên hơn.
     */
    private static void loadDotEnv() {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(System.getProperty("user.dir"))
                    .filename("env")
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();
            dotenv.entries().forEach(entry -> {
                if (System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
        } catch (Exception ex) {
            // File env là optional — giá trị có thể đến từ environment variable thật.
        }
    }

}
