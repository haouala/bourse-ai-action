package lu.bourse.engine.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataQualityServiceTest {
 @Test void blocksMissingPrices() {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class)))
   .thenAnswer(inv->{String sql=inv.getArgument(0);return sql.startsWith("SELECT currency")?List.of("USD"):List.of();});
  var history=mock(ClosedMarketHistory.class);
  when(history.load(eq("NVDA"),eq("1d"),eq(10000),any())).thenReturn(new ClosedMarketHistory.History("NVDA","1d","USD",List.of(),"NO_DATA",null));
  var report=new DataQualityService(jdbc,history,java.time.Clock.systemUTC()).check("NVDA","1d");
  assertThat(report.status()).isEqualTo("BLOCKED");
  assertThat(report.issues()).anyMatch(i->i.code().equals("NO_CANDLES"));
 }
 @Test void rejectsBadSymbols() {
  org.assertj.core.api.Assertions.assertThatThrownBy(()->new DataQualityService(mock(JdbcTemplate.class),mock(ClosedMarketHistory.class),java.time.Clock.systemUTC()).check("NVDA;DROP","1d"))
   .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
}
