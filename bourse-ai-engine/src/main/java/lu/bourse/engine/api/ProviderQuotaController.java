package lu.bourse.engine.api;

import lu.bourse.engine.provider.quota.ProviderQuotaService;
import lu.bourse.engine.provider.quota.QuotaExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/providers/quotas")
public class ProviderQuotaController {
    private final ProviderQuotaService quotas;

    public ProviderQuotaController(ProviderQuotaService quotas) { this.quotas = quotas; }

    @GetMapping
    public List<ProviderQuotaService.Usage> usage() { return quotas.listUsage(); }
}

@RestControllerAdvice
class ProviderQuotaErrorHandler {
    private final Clock clock;

    ProviderQuotaErrorHandler(Clock clock) { this.clock = clock; }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ProblemDetail> quotaExceeded(QuotaExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        problem.setTitle("Provider quota reached or provider disabled");
        problem.setProperty("provider", exception.provider());
        problem.setProperty("retryAt", exception.retryAt());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (exception.retryAt() != null) {
            long millis = Math.max(0, Duration.between(clock.instant(), exception.retryAt()).toMillis());
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (millis + 999) / 1000)));
        }
        return response.body(problem);
    }
}
