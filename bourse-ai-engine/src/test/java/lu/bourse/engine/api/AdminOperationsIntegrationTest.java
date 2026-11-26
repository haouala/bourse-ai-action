package lu.bourse.engine.api;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "admin.monitor-token=fixture-monitor-token-0123456789abcdef")
@AutoConfigureMockMvc
@Transactional
class AdminOperationsIntegrationTest extends DatabaseIntegrationSupport {
    private static final String TOKEN = "Bearer fixture-monitor-token-0123456789abcdef";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    @Test void requiresAdminAndRecordsAuthorizedFailuresWithoutStoringInput() throws Exception {
        String base = "/api/v1/admin";
        String action = base + "/operations/DAILY_IMPORT";
        var request = "{\"symbol\":\"SECURITYFIX\",\"number\":99,\"reason\":\"PRIVATE_INPUT\"}";
        mvc.perform(get(base + "/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(post(action).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM admin_operation_audit WHERE symbol='SECURITYFIX'", Long.class)).isZero();
        mvc.perform(post(action).header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "/dashboard").header("Authorization", TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.recentOperations[0].operation").value("DAILY_IMPORT"))
                .andExpect(jsonPath("$.recentOperations[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recentOperations[0].reason").doesNotExist())
                .andExpect(jsonPath("$.analyses24h").isNumber());
    }
}
