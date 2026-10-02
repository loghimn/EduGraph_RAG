package fu.gatewayservice;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class GatewayServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(GatewayServiceApplication.class);

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

    /**
     * Nạp file {@code env} thành system properties.
     * Tìm theo nhiều thư mục để hoạt động đúng dù IDE chạy từ repo root hay module dir.
     * Biến môi trường thật (IDE inject, CI, shell...) luôn được ưu tiên hơn.
     */
    private static void loadDotEnv() {
        String userDir = System.getProperty("user.dir");
        String[] candidates = {
                userDir,
                userDir + File.separator + "gateway-service"
        };

        for (String dir : candidates) {
            File envFile = new File(dir, "env");
            if (!envFile.isFile()) {
                continue;
            }
            try {
                Dotenv dotenv = Dotenv.configure()
                        .directory(dir)
                        .filename("env")
                        .ignoreIfMalformed()
                        .ignoreIfMissing()
                        .load();
                dotenv.entries().forEach(entry -> {
                    if (System.getenv(entry.getKey()) == null) {
                        System.setProperty(entry.getKey(), entry.getValue());
                    }
                });
                log.info("Loaded env file from {}", envFile.getAbsolutePath());
                return;
            } catch (Exception ex) {
                log.warn("Failed to load env file from {}: {}", envFile.getAbsolutePath(), ex.getMessage());
            }
        }
        log.warn("No env file found (searched: {}). "
                        + "Set JWT_SECRET, GATEWAY_PORT, CORE_SERVICE_URL as environment variables.",
                String.join(", ", candidates));
    }

}
