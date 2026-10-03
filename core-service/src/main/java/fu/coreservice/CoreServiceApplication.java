package fu.coreservice;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CoreServiceApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(CoreServiceApplication.class, args);
    }

    /**
     * Nạp file env cùng thư mục (core-service/env) vào system properties.
     * Không ghi đè environment variable đã export hay system property đã set.
     */
    private static void loadDotEnv() {
        try {
            Dotenv dotenv = Dotenv.configure().filename("env").load();
            dotenv.entries().forEach(entry -> {
                String key = entry.getKey();
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, entry.getValue());
                }
            });
        } catch (Exception ex) {
            // File env không tồn tại — chấp nhận dựa vào environment variable
        }
    }
}
