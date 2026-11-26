package lu.bourse.engine.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.service.HistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MarketAnalysisServiceTest {
    @Test
    void insufficientHistoryDoesNotWriteASnapshot() {
        var history = mock(HistoryService.class);
        var db = mock(JdbcTemplate.class);
        when(history.analyze("NVDA", "15m"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY));
        assertEquals("INSUFFICIENT_HISTORY",
                new MarketAnalysisService(history, db, new ObjectMapper()).refresh(1, "NVDA", "15m"));
        verifyNoInteractions(db);
    }
}
