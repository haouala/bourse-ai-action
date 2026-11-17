package lu.bourse.engine.provider;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run the same suite against a dedicated PostgreSQL test DB when explicitly configured. */
public abstract class DatabaseIntegrationSupport {
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
