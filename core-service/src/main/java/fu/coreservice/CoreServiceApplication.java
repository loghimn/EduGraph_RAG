package fu.coreservice;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class CoreServiceApplication {

    public static void main(String[] args) {
        // Load environment variables from .env file
        // Search current dir first, then parent dir (project root when running from core-service/)
        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .filename(".env")
                .ignoreIfMissing()
                .load();

        // If .env not found in current dir, try parent directory (project root)
        if (dotenv == null || dotenv.entries().isEmpty()) {
            File parentEnv = new File(System.getProperty("user.dir"), "../.env");
            if (parentEnv.exists()) {
                dotenv = Dotenv.configure()
                        .directory(parentEnv.getParent())
                        .filename(".env")
                        .ignoreIfMissing()
                        .load();
            }
        }

        // Set system properties from .env (only if not already set by OS env)
        if (dotenv != null) {
            dotenv.entries().forEach(entry -> {
                if (System.getProperty(entry.getKey()) == null
                        && System.getenv(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
        }

        SpringApplication.run(CoreServiceApplication.class, args);
    }

}
