package fu.gatewayservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * Public endpoint whitelist (no JWT required).
 * Patterns support Ant-style {@code /**} wildcards.
 */
@Component
public class PublicPaths {

    private final List<String> patterns;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public PublicPaths(@Value("${gateway.security.public-paths}") String csv) {
        this.patterns = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean matches(String path) {
        return patterns.stream().anyMatch(p -> matcher.match(p, path));
    }

    public List<String> getPatterns() {
        return patterns;
    }
}
