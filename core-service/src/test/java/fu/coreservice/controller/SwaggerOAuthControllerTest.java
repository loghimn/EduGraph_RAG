package fu.coreservice.controller;

import fu.coreservice.service.AuthService;
import fu.coreservice.security.LoginAuthorizationRequestRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SwaggerOAuthControllerTest {
    @ParameterizedTest
    @CsvSource({"prod,false", "prod,true", "prod,yes", "prod,", "'prod,dev',true", "dev,false", "dev,yes", "dev,on", "dev,1", "dev,''", "dev,flase"})
    void routeIsNotMappedWhenProductionOrSwaggerDisabled(String profile, String swaggerEnabled) throws Exception {
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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"true", "TRUE"})
    void developmentRouteStartsNormalGoogleLoginWithoutRetainingCallerRedirect(String swaggerEnabled) throws Exception {
        try (var context = context("dev", swaggerEnabled)) {
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/auth/google/authorize")
                            .param("state", "swagger-state")
                            .param("redirect_uri", "https://attacker.example/collect"))
                    .andExpect(status().isFound())
                    .andReturn();
            var redirect = result.getResponse().getRedirectedUrl();
            assertNotNull(redirect);
            assertTrue(redirect.startsWith("https://accounts.example/authorize?"));
            var googleState = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("state");
            assertNotNull(googleState);
            assertNotEquals("swagger-state", googleState);
            var session = result.getRequest().getSession(false);
            assertNotNull(session);
            assertNull(session.getAttribute("fromSwagger"));
            assertNull(session.getAttribute("swagger_state"));
            assertNull(session.getAttribute("swagger_redirect_uri"));
            var callback = new MockHttpServletRequest();
            callback.setSession(session);
            callback.setParameter("state", UriUtils.decode(googleState, java.nio.charset.StandardCharsets.UTF_8));
            var authorization = context.getBean(LoginAuthorizationRequestRepository.class).loadAuthorizationRequest(callback);
            assertNotNull(authorization);
            assertEquals(Boolean.TRUE, authorization.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_MODE));
            assertEquals("swagger-state", authorization.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_STATE));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/login/oauth2/code/google",
            "http://localhost:8080/{action}/oauth2/code/{registrationId}",
            "{baseUrl}/{action}/oauth2/code/{registrationId}"
    })
    void providerCallbackMatchesNormalGoogleLoginForRedirectTemplates(String redirectTemplate) throws Exception {
        try (var context = context("dev", "true", redirectTemplate)) {
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/auth/google/authorize").param("state", "swagger-state")
                            .with(request -> { request.setServerPort(8080); return request; }))
                    .andExpect(status().isFound()).andReturn();
            var query = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                    .build().getQueryParams();
            String providerCallback = UriUtils.decode(query.getFirst("redirect_uri"), java.nio.charset.StandardCharsets.UTF_8);
            var normalRequest = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
            normalRequest.setServletPath("/oauth2/authorization/google");
            normalRequest.setServerPort(8080);
            var normal = new DefaultOAuth2AuthorizationRequestResolver(
                    context.getBean(ClientRegistrationRepository.class), "/oauth2/authorization").resolve(normalRequest);
            assertNotNull(normal);
            assertEquals("http://localhost:8080/login/oauth2/code/google", providerCallback);
            assertEquals(normal.getRedirectUri(), providerCallback);
            var callback = new MockHttpServletRequest();
            callback.setSession(result.getRequest().getSession(false));
            callback.setParameter("state", UriUtils.decode(query.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8));
            var saved = context.getBean(LoginAuthorizationRequestRepository.class).loadAuthorizationRequest(callback);
            assertNotNull(saved);
            assertEquals(providerCallback, saved.getRedirectUri());
            assertEquals(Boolean.TRUE, saved.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_MODE));
            assertEquals("swagger-state", saved.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_STATE));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"authorize", "login", "", "custom"})
    void swaggerRouteAlwaysUsesLoginCallbackRegardlessOfCallerAction(String action) throws Exception {
        try (var context = context("dev", "true", "http://localhost:8080/{action}/oauth2/code/{registrationId}")) {
            var request = get("/api/auth/google/authorize").param("state", "swagger-state");
            if (action != null) { request.param("action", action); }
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(request).andExpect(status().isFound()).andReturn();
            var query = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                    .build().getQueryParams();
            String redirectUri = UriUtils.decode(query.getFirst("redirect_uri"), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("http://localhost:8080/login/oauth2/code/google", redirectUri);
            var callback = new MockHttpServletRequest();
            callback.setSession(result.getRequest().getSession(false));
            callback.setParameter("state", UriUtils.decode(query.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8));
            var saved = context.getBean(LoginAuthorizationRequestRepository.class).loadAuthorizationRequest(callback);
            assertNotNull(saved);
            assertEquals(redirectUri, saved.getRedirectUri());
            assertEquals(Boolean.TRUE, saved.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_MODE));
            assertEquals("swagger-state", saved.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_STATE));
        }
    }

    private AnnotationConfigWebApplicationContext context(String profile, String swaggerEnabled) {
        return context(profile, swaggerEnabled, "http://localhost/login/oauth2/code/google");
    }

    private AnnotationConfigWebApplicationContext context(String profile, String swaggerEnabled, String redirectTemplate) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profile.split(","));
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "test.google.redirect-uri=" + redirectTemplate);
        if (swaggerEnabled != null) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "springdoc.swagger-ui.enabled=" + swaggerEnabled);
        }
        context.register(TestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    @Import({AuthController.class, SwaggerOAuthController.class})
    static class TestConfig {
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean LoginAuthorizationRequestRepository authorizationRequests() { return new LoginAuthorizationRequestRepository(); }
        @Bean ClientRegistrationRepository registrations(Environment environment) {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test").clientSecret("test").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(environment.getRequiredProperty("test.google.redirect-uri")).authorizationUri("https://accounts.example/authorize")
                    .tokenUri("https://accounts.example/token").userInfoUri("https://accounts.example/userinfo")
                    .userNameAttributeName("email").scope("email").build());
        }
    }
}
