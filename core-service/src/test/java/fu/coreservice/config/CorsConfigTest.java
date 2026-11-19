package fu.coreservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    @ParameterizedTest
    @ValueSource(strings = {"*", "https://edugraph-rag.io.vn, *"})
    void credentialedWildcardIsRejectedAtStartup(String origins) {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "app.cors.allowed-origins=" + origins);
            context.register(CorsConfig.class);
            var failure = assertThrows(BeanCreationException.class, context::refresh);
            var cause = assertInstanceOf(IllegalArgumentException.class, failure.getMostSpecificCause());
            assertTrue(cause.getMessage().contains("allowCredentials"));
        }
    }

    @Test
    void explicitOriginListRemainsValid() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "app.cors.allowed-origins= https://edugraph-rag.io.vn, ,http://localhost:5173 ");
            context.register(CorsConfig.class);
            context.refresh();
            var filter = context.getBean(CorsFilter.class);
            for (var origin : new String[]{"https://edugraph-rag.io.vn", "http://localhost:5173"}) {
                var response = preflight(filter, origin);
                assertEquals(200, response.getStatus());
                assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"));
                assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
            }
        }
    }

    @Test
    void localhostFallbackRemainsValid() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(CorsConfig.class)) {
            var filter = context.getBean(CorsFilter.class);
            for (var port : new int[]{3000, 5173, 8080, 8081}) {
                var origin = "http://localhost:" + port;
                var response = preflight(filter, origin);
                assertEquals(200, response.getStatus());
                assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"));
                assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
            }
            assertEquals(403, preflight(filter, "https://unconfigured.example").getStatus());
        }
    }

    @Test
    void productionOriginIsAcceptedAndLocalhostIsRejected() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "app.cors.allowed-origins= https://edugraph-rag.io.vn ");
            context.register(CorsConfig.class);
            context.refresh();
            var filter = context.getBean(CorsFilter.class);

            var accepted = preflight(filter, "https://edugraph-rag.io.vn");
            assertEquals(200, accepted.getStatus());
            assertEquals("https://edugraph-rag.io.vn", accepted.getHeader("Access-Control-Allow-Origin"));
            assertEquals("true", accepted.getHeader("Access-Control-Allow-Credentials"));
            var rejected = preflight(filter, "http://localhost:5173");
            assertEquals(403, rejected.getStatus());
            assertNull(rejected.getHeader("Access-Control-Allow-Origin"));
        }
    }

    private MockHttpServletResponse preflight(CorsFilter filter, String origin) throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api/users/me");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "Authorization");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Preflight should be handled by CORS"));
        return response;
    }
}
