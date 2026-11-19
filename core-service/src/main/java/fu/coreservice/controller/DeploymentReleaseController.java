package fu.coreservice.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("prod")
public class DeploymentReleaseController {
    private final String backendReleaseId;

    public DeploymentReleaseController(@Value("${APP_RELEASE_ID:development}") String backendReleaseId) {
        this.backendReleaseId = backendReleaseId;
    }

    @GetMapping("/api/deployment/release")
    public ResponseEntity<Map<String, String>> release() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(Map.of("backendReleaseId", backendReleaseId));
    }
}
