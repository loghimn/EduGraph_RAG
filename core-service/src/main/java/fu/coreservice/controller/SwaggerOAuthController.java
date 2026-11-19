package fu.coreservice.controller;

import io.swagger.v3.oas.annotations.Hidden;
import fu.coreservice.config.SwaggerOAuthCondition;
import fu.coreservice.security.LoginAuthorizationRequestRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Hidden
@RestController
@Conditional(SwaggerOAuthCondition.class)
public class SwaggerOAuthController {
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final LoginAuthorizationRequestRepository authorizationRequests;

    public SwaggerOAuthController(ClientRegistrationRepository registrations, LoginAuthorizationRequestRepository authorizationRequests) {
        this.resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        this.authorizationRequests = authorizationRequests;
    }

    @GetMapping("/api/auth/google/authorize")
    public void authorize(@RequestParam(value = "state", required = false) String state,
                          HttpServletRequest request, HttpServletResponse response) throws IOException {
        // The explicit-registration overload defaults {action} to "authorize";
        // always use the login callback, regardless of a caller-supplied action.
        var loginRequest = new HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                return "action".equals(name) ? "login" : super.getParameter(name);
            }
        };
        var resolved = resolver.resolve(loginRequest, "google");
        if (resolved == null) { throw new IllegalStateException("Google authorization request is unavailable"); }
        var authorizationRequest = OAuth2AuthorizationRequest.from(resolved).attributes(attributes -> {
            attributes.put(LoginAuthorizationRequestRepository.SWAGGER_MODE, true);
            attributes.put(LoginAuthorizationRequestRepository.SWAGGER_STATE, state == null ? "" : state);
        }).build();
        authorizationRequests.saveAuthorizationRequest(authorizationRequest, request, response);
        response.sendRedirect(authorizationRequest.getAuthorizationRequestUri());
    }
}
