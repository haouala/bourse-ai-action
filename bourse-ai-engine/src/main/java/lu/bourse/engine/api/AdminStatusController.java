package lu.bourse.engine.api;

import jakarta.servlet.http.HttpServletResponse;
import lu.bourse.engine.admin.AdminStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminStatusController {
    private final AdminStatusService status;
    private final String token;

    public AdminStatusController(AdminStatusService status, @Value("${admin.monitor-token:}") String token) {
        this.status = status;
        this.token = token;
    }

    @GetMapping("/status")
    public AdminStatusService.Snapshot status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (token.length() < 32) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Administration monitoring is not configured");
        }
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(UNAUTHORIZED, "Administrator credential required");
        }
        return status.read();
    }
}
