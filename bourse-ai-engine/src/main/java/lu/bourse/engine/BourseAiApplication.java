package lu.bourse.engine;

import lu.bourse.engine.provider.quota.QuotaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(QuotaProperties.class)
@EnableScheduling
public class BourseAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(BourseAiApplication.class, args);
    }

    @Bean
    Clock quotaClock() {
        return Clock.systemUTC();
    }
}

