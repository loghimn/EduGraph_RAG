package fu.coreservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        String bearerAuthName = "bearerAuth";
        String googleOAuth2Name = "googleOAuth2";

        OAuthFlow googleAuthFlow = new OAuthFlow()
                .authorizationUrl("http://localhost:8080/api/auth/google/authorize")
                .scopes(new Scopes()
                        .addString("email", "Access your email")
                        .addString("profile", "Access your profile"));

        SecurityScheme bearerAuthScheme = new SecurityScheme()
                .name(bearerAuthName)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste your JWT access token here. "
                        + "Get token from: POST /api/auth/login");

        SecurityScheme googleOAuth2Scheme = new SecurityScheme()
                .name(googleOAuth2Name)
                .type(SecurityScheme.Type.OAUTH2)
                .description("Google login — leave client_id empty, just click Authorize")
                .flows(new OAuthFlows()
                        .implicit(googleAuthFlow));

        return new OpenAPI()
                .info(new Info()
                        .title("EduGraph RAG - Authentication API")
                        .description("API Documentation for EduGraph RAG Authentication System")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("EduGraph Team")
                                .email("team@edugraph.com")))
                .addSecurityItem(new SecurityRequirement()
                        .addList(bearerAuthName)
                        .addList(googleOAuth2Name))
                .components(new Components()
                        .addSecuritySchemes(bearerAuthName, bearerAuthScheme)
                        .addSecuritySchemes(googleOAuth2Name, googleOAuth2Scheme));
    }
}
