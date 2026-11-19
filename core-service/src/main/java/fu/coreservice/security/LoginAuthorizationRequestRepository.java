package fu.coreservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class LoginAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    public static final String SWAGGER_MODE = LoginAuthorizationRequestRepository.class.getName() + ".swagger";
    public static final String SWAGGER_STATE = LoginAuthorizationRequestRepository.class.getName() + ".swaggerState";
    public static final String CALLBACK_REQUEST = LoginAuthorizationRequestRepository.class.getName() + ".callback";

    private final HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        request.removeAttribute(CALLBACK_REQUEST);
        // The delegate returns a request only when the callback's OAuth state matches.
        var authorizationRequest = delegate.removeAuthorizationRequest(request, response);
        if (authorizationRequest != null) { request.setAttribute(CALLBACK_REQUEST, authorizationRequest); }
        return authorizationRequest;
    }
}
