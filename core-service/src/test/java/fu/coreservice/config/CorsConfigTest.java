package fu.coreservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
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
