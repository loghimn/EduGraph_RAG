package fu.coreservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
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

        SecurityScheme bearerAuth = new SecurityScheme()
                .name("bearerAuth")
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste JWT access token here");

        OAuthFlow googleAuthFlow = new OAuthFlow()
                .authorizationUrl("http://localhost:8080/api/auth/google/authorize")
                .scopes(new Scopes()
                        .addString("email", "Access your email")
                        .addString("profile", "Access your profile"));

        SecurityScheme googleOAuth2 = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Sign in with Google")
                .flows(new OAuthFlows().implicit(googleAuthFlow));

        return new OpenAPI()
                .info(new Info()
                        .title("EduGraph RAG API")
                        .version("1.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("googleOAuth2"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", bearerAuth)
                        .addSecuritySchemes("googleOAuth2", googleOAuth2));
    }
}
