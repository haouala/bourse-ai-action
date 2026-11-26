package lu.bourse.engine.api;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "admin.monitor-token=fixture-monitor-token-0123456789abcdef")
@AutoConfigureMockMvc
@Transactional
class RelationshipSuggestionIntegrationTest extends DatabaseIntegrationSupport {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;

    @Test void suggestionsRequireExplicitApprovalAndAreConsumedByTheExistingGraph() throws Exception {
        for (String symbol : new String[]{"AMD", "NVDA"}) {
            db.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,'STOCK','USD') ON CONFLICT DO NOTHING",
                    symbol, symbol);
        }
        String base = "/api/v1/relationships/NVDA";
        String confirm = base + "/suggestions/amd-nvda-discrete-gpu/confirm";
        mvc.perform(get(base + "/suggestions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evidence.id").value("amd-nvda-discrete-gpu"));
        mvc.perform(get(base)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post(confirm)).andExpect(status().isUnauthorized());
        mvc.perform(post(confirm).header("Authorization", "Bearer fixture-monitor-token-0123456789abcdef"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationshipType").value("COMPETES_WITH"));
        mvc.perform(get(base)).andExpect(jsonPath("$[0].fromSymbol").value("AMD"));
        mvc.perform(get(base + "/suggestions")).andExpect(jsonPath("$.length()").value(0));
    }
}
