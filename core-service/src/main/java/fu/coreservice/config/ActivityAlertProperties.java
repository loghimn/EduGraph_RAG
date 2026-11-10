package fu.coreservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.activity-alerts")
@Getter
@Setter
public class ActivityAlertProperties {
    private int loginFailureThreshold = 5;
    private int loginWindowSeconds = 900;
    private int systemFailureThreshold = 3;
    private int systemWindowSeconds = 300;
    private int maxTrackedSubjects = 10000;
}
