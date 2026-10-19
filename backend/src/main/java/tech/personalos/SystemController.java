package tech.personalos;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class SystemController {
    private final String version;

    SystemController(@Value("${app.version}") String version) {
        this.version = version;
    }

    @GetMapping({"", "/", "/status"})
    SystemStatus status() {
        return new SystemStatus("Personal OS API", "UP", version, Instant.now(), List.of(
                new Module("budgeting", "Budgeting", "planned"),
                new Module("running", "Running", "planned"),
                new Module("network", "Network calculations", "planned")));
    }

    record SystemStatus(String service, String status, String version, Instant timestamp, List<Module> modules) {}
    record Module(String id, String name, String status) {}
}
