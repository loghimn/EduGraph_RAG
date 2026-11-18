package fu.coreservice.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Hidden
@RestController
@Profile("!prod")
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true", matchIfMissing = true)
public class SwaggerOAuthController {
    @GetMapping("/api/auth/google/authorize")
    public void authorize(@RequestParam(value = "state", required = false) String state,
                          HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession();
        session.setAttribute("fromSwagger", true);
        session.setAttribute("swagger_state", state);
        // Never retain a caller-supplied redirect target, including from an older flow.
        session.removeAttribute("swagger_redirect_uri");
        response.sendRedirect("/oauth2/authorization/google");
    }
}
