package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ForecastChartIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired ForecastChartService charts;
 @Autowired MockMvc mvc;

 @Test void returnsDistinctChartSeriesWithHonestMissingValuesAndNoWrites() throws Exception{
  String symbol="CHARTFIXTURE";
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
   symbol,"Chart fixture","STOCK","USD");
  Long id=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  try{
   double value=100;
   for(int i=0;i<950;i++){
    value*=Math.exp(.0003+.009*Math.sin(i*.72)+.007*Math.cos(i*.37));
    BigDecimal close=BigDecimal.valueOf(value);
    LocalDate date=LocalDate.now(ZoneOffset.UTC).minusDays(950-i);
    jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
     id,Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()),
     close,close.multiply(new BigDecimal("1.1")),close.multiply(new BigDecimal("0.9")),close);
   }
   int before=jdbc.queryForObject("SELECT COUNT(*) FROM prediction_records WHERE asset_id=?",Integer.class,id);
   var history=charts.historical(symbol,50);
   assertThat(history.points()).hasSize(50);
   assertThat(history.points().get(0).timestamp()).isBefore(history.points().get(49).timestamp());
   assertThat(history.seriesType()).isEqualTo("OBSERVED_DAILY_CLOSE");
   var scenarios=charts.scenarios(symbol);
   assertThat(scenarios.points()).hasSize(4);
   assertThat(scenarios.points().stream().map(ForecastChartService.ScenarioPoint::horizonDays))
    .containsExactly(7,30,90,180);
   assertThat(scenarios.points()).allSatisfy(p->{
    assertThat(p.pessimistic()).isLessThanOrEqualTo(p.central());
    assertThat(p.central()).isLessThanOrEqualTo(p.optimistic());
   });
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_records WHERE asset_id=?",Integer.class,id))
    .isEqualTo(before);
   var pending=jdbc.queryForObject("SELECT close_price FROM market_candles WHERE asset_id=? ORDER BY open_time DESC LIMIT 1",
    BigDecimal.class,id);
   Instant now=Instant.now(),future=now.plus(Duration.ofDays(30));
   jdbc.update("INSERT INTO prediction_records(asset_id,model_name,model_version,horizon_days,predicted_price,reference_price,reference_time,predicted_at,target_time) VALUES (?, 'TEST', 'v1',30,?,?,?,?,?)",
    id,pending,pending,Timestamp.from(now.minus(Duration.ofDays(1))),Timestamp.from(now),Timestamp.from(future));
   var errors=charts.predictionErrors(symbol,10);
   assertThat(errors.points()).singleElement().satisfies(p->{
    assertThat(p.status()).isEqualTo("PENDING");
    assertThat(p.realizedPrice()).isNull();
    assertThat(p.absoluteErrorPct()).isNull();
    assertThat(p.observedAt()).isNull();
   });
   mvc.perform(get("/api/v1/charts/"+symbol+"/history").param("limit","30"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(30));
   mvc.perform(get("/api/v1/charts/"+symbol+"/scenarios"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(4));
   mvc.perform(get("/api/v1/charts/"+symbol+"/predictions"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.points[0].status").value("PENDING"))
    .andExpect(jsonPath("$.points[0].realizedPrice").doesNotExist());
   mvc.perform(get("/api/v1/charts/"+symbol+"/ml"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
   mvc.perform(get("/api/v1/charts/"+symbol+"/history").param("limit","1001"))
    .andExpect(status().isBadRequest());
   mvc.perform(get("/api/v1/charts/"+symbol+"/predictions").param("limit","201"))
    .andExpect(status().isBadRequest());
  }finally{
   jdbc.update("DELETE FROM prediction_records WHERE asset_id=?",id);
   jdbc.update("DELETE FROM ml_training_runs WHERE asset_id=?",id);
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",id);
   jdbc.update("DELETE FROM assets WHERE id=?",id);
  }
 }
}
