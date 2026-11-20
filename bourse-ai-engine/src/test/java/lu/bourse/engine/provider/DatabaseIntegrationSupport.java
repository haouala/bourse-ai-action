package lu.bourse.engine.provider;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run the same suite against a dedicated PostgreSQL test DB when explicitly configured. */
public abstract class DatabaseIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate cleanup;

    @org.junit.jupiter.api.BeforeEach
    void clearDependentTables() {
        for(String table:new String[]{"explanation_factors","explanation_snapshots","seasonality_runs","daily_series_qualifications","asset_market_policies","information_mentions","information_attributions","information_items","information_sync_runs"})cleanup.update("DELETE FROM "+table);
        cleanup.update("UPDATE information_sources SET publisher_actor_id=NULL,enabled=FALSE,etag=NULL,last_modified=NULL,next_attempt_at=NULL,lease_until=NULL,lease_token=NULL,last_success_at=NULL,last_error=NULL");
        for(String table:new String[]{"social_impact_scores","social_asset_assessments","social_event_publications","social_events","social_publications","actor_influence_profiles","social_api_reservations","social_actors","trade_alerts","portfolio_risk_snapshots","position_marks","position_trades","positions","portfolios","market_sync_tasks","watchlist"})
            cleanup.update("DELETE FROM "+table);
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("BOURSE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv("BOURSE_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("BOURSE_TEST_DB_PASSWORD"));
        }
    }
}

