package lu.bourse.engine.api;

import lu.bourse.engine.news.NewsCollectionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/**
 * Local operator endpoints until authentication is implemented. Never expose publicly.
 */
@RestController
@RequestMapping("/api/v1/news-collection")
public class NewsCollectionController {
    private final NewsCollectionService service;
    private final JdbcTemplate db;
    public NewsCollectionController(NewsCollectionService service,JdbcTemplate db) {
        this.service=service;this.db=db;
    }
    @GetMapping("/status")
    public Map<String,Object> status(){return service.status();}

    /** method=GKG, NGRAMS or RSS; NGRAMS may use a published YYYYMMDDHHmm00 slot. */
    @PostMapping("/run")
    public NewsCollectionService.Run run(@RequestParam String method,
        @RequestParam(required=false) String slot){return service.run(method.toUpperCase(Locale.ROOT),slot);}

    /** Read-only merged bulk archive news; never contacts external providers. */
    @GetMapping("/items")
    public List<Map<String,Object>> items(@RequestParam(defaultValue="100") int limit) {
        if(limit<1||limit>200)throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,"limit must be 1..200");
        return db.queryForList("""
            SELECT a.symbol,n.title,n.url,n.source,n.published_time
            FROM market_news n JOIN assets a ON a.id=n.asset_id
            WHERE n.source IN ('GDELT_NGRAMS','GDELT_GKG')
            ORDER BY n.id DESC LIMIT ?
            """,limit);
    }
}
