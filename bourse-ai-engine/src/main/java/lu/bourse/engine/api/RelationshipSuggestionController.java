package lu.bourse.engine.api;

import lu.bourse.engine.service.RelationshipSuggestionService;
import lu.bourse.engine.service.StockRelationshipService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/relationships/{symbol}/suggestions")
public class RelationshipSuggestionController {
    public record Candidate(RelationshipSuggestionService.Suggestion evidence, boolean peerExists) {}

    private final RelationshipSuggestionService suggestions;
    private final String token;

    public RelationshipSuggestionController(RelationshipSuggestionService suggestions,
                                            @Value("${admin.monitor-token:}") String token) {
        this.suggestions = suggestions;
        this.token = token;
    }

    @GetMapping
    public List<Candidate> list(@PathVariable String symbol) {
        return suggestions.forSymbol(symbol).stream().map(suggestion ->
                new Candidate(suggestion, suggestions.peerExists(suggestion.fromSymbol().equalsIgnoreCase(symbol)
                        ? suggestion.toSymbol() : suggestion.fromSymbol()))).toList();
    }

    @PostMapping("/{id}/confirm")
    public StockRelationshipService.Edge confirm(@PathVariable String symbol, @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AdminStatusController.requireToken(token, authorization);
        return suggestions.confirm(symbol, id);
    }
}
