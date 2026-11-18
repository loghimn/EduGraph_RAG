package fu.coreservice.controller;

import fu.coreservice.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SwaggerOAuthControllerTest {
    @ParameterizedTest
    @CsvSource({"prod,false", "prod,true", "dev,false"})
    void routeIsNotMappedWhenProductionOrSwaggerDisabled(String profile, boolean swaggerEnabled) throws Exception {
        try (var context = context(profile, swaggerEnabled)) {
            assertTrue(context.getBeansOfType(SwaggerOAuthController.class).isEmpty());
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/auth/google/authorize")
                            .param("redirect_uri", "https://attacker.example/collect"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("Location"))
                    .andReturn();
            assertNull(result.getRequest().getSession(false));
        }
    }

    @Test
    void developmentRouteStartsNormalGoogleLoginWithoutRetainingCallerRedirect() throws Exception {
        try (var context = context("dev", true)) {
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/auth/google/authorize")
                            .param("state", "swagger-state")
                            .param("redirect_uri", "https://attacker.example/collect"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/oauth2/authorization/google"))
                    .andReturn();
            var session = result.getRequest().getSession(false);
            assertNotNull(session);
            assertEquals(true, session.getAttribute("fromSwagger"));
            assertEquals("swagger-state", session.getAttribute("swagger_state"));
            assertNull(session.getAttribute("swagger_redirect_uri"));
        }
    }

    private AnnotationConfigWebApplicationContext context(String profile, boolean swaggerEnabled) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profile);
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "springdoc.swagger-ui.enabled=" + swaggerEnabled);
        context.register(TestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    @Import({AuthController.class, SwaggerOAuthController.class})
    static class TestConfig {
        @Bean AuthService authService() { return mock(AuthService.class); }
    }
}
