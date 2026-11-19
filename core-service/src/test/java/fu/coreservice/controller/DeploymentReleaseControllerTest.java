package fu.coreservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeploymentReleaseControllerTest {
    @Test
    void releaseProbeReturnsConfiguredIdentifierWithoutCaching() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DeploymentReleaseController("a".repeat(64))).build();
        mvc.perform(get("/api/deployment/release"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.backendReleaseId").value("a".repeat(64)));
    }
}
