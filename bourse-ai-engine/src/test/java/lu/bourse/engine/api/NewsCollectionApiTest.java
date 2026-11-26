package lu.bourse.engine.api;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class NewsCollectionApiTest extends DatabaseIntegrationSupport {
    @Autowired MockMvc mvc;

    @Test void statusAndItemsAreLocalAndJsonSafe()throws Exception {
        mvc.perform(get("/api/v1/news-collection/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.automaticEnabled").value(false))
            .andExpect(jsonPath("$.methods.length()").value(3));
        mvc.perform(get("/api/v1/news-collection/items").param("limit","0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("HTTP_400"))
            .andExpect(jsonPath("$.severity").value("ERROR"))
            .andExpect(header().exists("X-Request-ID"));
    }

    @Test void invalidCollectMethodAndSlotProduceStructuredErrors()throws Exception {
        mvc.perform(post("/api/v1/news-collection/run").param("method","UNKNOWN"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("NEWS_METHOD_INVALID"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.retryable").value(false));
        mvc.perform(post("/api/v1/news-collection/run").param("method","NGRAMS").param("slot","../private"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("NEWS_SLOT_INVALID"));
    }
}
