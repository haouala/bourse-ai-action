package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class RelationshipSuggestionService {
    public record Suggestion(String id, String fromSymbol, String toSymbol, String relationshipType,
                             String evidenceUrl, String evidenceSummary, LocalDate evidenceDate) {
        String peer(String root) {
            return fromSymbol.equals(root) ? toSymbol : fromSymbol;
        }
    }

    // Primary-source evidence reviewed for this initial catalog.
    private static final String MICROSOFT_REPORT = "https://www.microsoft.com/investor/reports/ar24/index.html";
    private static final List<Suggestion> CATALOG = List.of(
            new Suggestion("amd-nvda-discrete-gpu", "AMD", "NVDA", "COMPETES_WITH",
                    "https://ir.amd.com/financial-information/sec-filings/content/0000002488-26-000018/amd-20251227.htm",
                    "Le rapport annuel d’AMD désigne NVIDIA comme concurrent dans les GPU dédiés.",
                    LocalDate.of(2026, 2, 4)),
            new Suggestion("amzn-msft-cloud", "AMZN", "MSFT", "COMPETES_WITH", MICROSOFT_REPORT,
                    "Le rapport annuel de Microsoft cite Amazon parmi les concurrents d’Azure.",
                    LocalDate.of(2024, 7, 30)),
            new Suggestion("googl-msft-cloud", "GOOGL", "MSFT", "COMPETES_WITH", MICROSOFT_REPORT,
                    "Le rapport annuel de Microsoft cite Google parmi les concurrents d’Azure.",
                    LocalDate.of(2024, 7, 30)));

    private final StockRelationshipService relationships;
    private final JdbcTemplate jdbc;

    public RelationshipSuggestionService(StockRelationshipService relationships, JdbcTemplate jdbc) {
        this.relationships = relationships;
        this.jdbc = jdbc;
    }

    public List<Suggestion> forSymbol(String symbol) {
        String root = symbol.toUpperCase(Locale.ROOT);
        var recorded = relationships.direct(root, true);
        return CATALOG.stream().filter(s -> s.fromSymbol().equals(root) || s.toSymbol().equals(root))
                .filter(s -> recorded.stream().noneMatch(edge ->
                        edge.relationshipType().equals(s.relationshipType()) &&
                        ((edge.fromSymbol().equals(root) && edge.toSymbol().equals(s.peer(root))) ||
                         (edge.toSymbol().equals(root) && edge.fromSymbol().equals(s.peer(root))))))
                .toList();
    }

    public StockRelationshipService.Edge confirm(String symbol, String id) {
        Suggestion suggestion = forSymbol(symbol).stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion unavailable"));
        try {
            return relationships.add(new StockRelationshipService.Submission(suggestion.fromSymbol(),
                    suggestion.toSymbol(), suggestion.relationshipType(), "HIGH", suggestion.evidenceUrl(),
                    suggestion.evidenceSummary(), suggestion.evidenceDate(), null, null));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Relationship already recorded");
        }
    }

    public boolean peerExists(String symbol) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM assets WHERE symbol=?)",
                Boolean.class, symbol));
    }
}
