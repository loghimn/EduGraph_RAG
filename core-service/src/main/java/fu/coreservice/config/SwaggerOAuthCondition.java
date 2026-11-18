package fu.coreservice.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class SwaggerOAuthCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isEnabled(context.getEnvironment());
    }

    public static boolean isEnabled(Environment environment) {
        // Match the original havingValue="true", matchIfMissing=true semantics.
        return !environment.acceptsProfiles(Profiles.of("prod"))
                && "true".equalsIgnoreCase(environment.getProperty("springdoc.swagger-ui.enabled", "true"));
    }
}
