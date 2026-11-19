package fu.gatewayservice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=prod",
        "jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtdGhhdC1pcy1hdC1sZWFzdC0zMi1ieXRlcw=="
})
class ProductionHealthTest {
    @Autowired
    private Environment environment;

    @Test
    void containerHealthCheckWorksWithoutJwtAndProductionCorsHeadersAreDeduplicated() throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UP\""));
        }
        assertEquals("DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin",
                environment.getProperty("spring.cloud.gateway.server.webflux.default-filters[0]"));
    }
}
