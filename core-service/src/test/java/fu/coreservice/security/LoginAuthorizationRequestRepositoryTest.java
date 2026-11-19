package fu.coreservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.junit.jupiter.api.Assertions.*;

class LoginAuthorizationRequestRepositoryTest {
    private final LoginAuthorizationRequestRepository repository = new LoginAuthorizationRequestRepository();
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void matchingSwaggerCallbackRecoversOnlyItsOwnStateOnce() {
        var authorization = authorization("google-swagger", true);
        repository.saveAuthorizationRequest(authorization, request(null), response);
        var callback = request("google-swagger");
        assertSame(authorization, repository.removeAuthorizationRequest(callback, response));
        var context = (OAuth2AuthorizationRequest) callback.getAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST);
        assertEquals(Boolean.TRUE, context.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_MODE));
        assertEquals("swagger-client-state", context.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_STATE));
        assertNull(repository.removeAuthorizationRequest(callback, response));
        assertNull(callback.getAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST));
    }

    @Test
    void normalAuthorizationReplacesAbandonedSwaggerContext() {
        repository.saveAuthorizationRequest(authorization("abandoned-swagger", true), request(null), response);
        var normal = authorization("normal-login", false);
        repository.saveAuthorizationRequest(normal, request(null), response);
        var normalCallback = request("normal-login");
        assertSame(normal, repository.removeAuthorizationRequest(normalCallback, response));
        var context = (OAuth2AuthorizationRequest) normalCallback.getAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST);
        assertNull(context.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_MODE));
        assertNull(context.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_STATE));
        assertNull(repository.removeAuthorizationRequest(request("abandoned-swagger"), response));
    }

    @Test
    void wrongCallbackStateCannotRecoverOrConsumeSwaggerContext() {
        var authorization = authorization("correct-state", true);
        repository.saveAuthorizationRequest(authorization, request(null), response);
        var wrongCallback = request("wrong-state");
        assertNull(repository.removeAuthorizationRequest(wrongCallback, response));
        assertNull(wrongCallback.getAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST));
        assertSame(authorization, repository.removeAuthorizationRequest(request("correct-state"), response));
    }

    private MockHttpServletRequest request(String state) {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        if (state != null) { request.setParameter("state", state); }
        return request;
    }

    private OAuth2AuthorizationRequest authorization(String state, boolean swagger) {
        var builder = OAuth2AuthorizationRequest.authorizationCode().clientId("test")
                .authorizationUri("https://accounts.example/authorize")
                .redirectUri("http://localhost/login/oauth2/code/google").state(state);
        if (swagger) {
            builder.attributes(attributes -> {
                attributes.put(LoginAuthorizationRequestRepository.SWAGGER_MODE, true);
                attributes.put(LoginAuthorizationRequestRepository.SWAGGER_STATE, "swagger-client-state");
            });
        }
        return builder.build();
    }
}
