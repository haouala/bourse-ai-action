package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PredictionTrackingIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired PredictionTrackingService tracking;

 @Test void recordsThenSettlesOnlyAfterTargetAndNeverDoubleCounts() {
  String symbol="PREDTRACK";
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",symbol,"Prediction fixture","STOCK","USD");
  Long assetId=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  try {
   for(int i=0;i<45;i++){
    Instant day=Instant.now().minus(46-i,ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
    BigDecimal close=BigDecimal.valueOf(100+i);
    jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
     assetId,Timestamp.from(day),close,close.add(BigDecimal.TEN),close.subtract(BigDecimal.TEN),close);
   }
   var prediction=tracking.submit(new PredictionTrackingService.Submission(symbol,7,new BigDecimal("130"),"TEST_MODEL","v1"));
   assertThat(prediction.status()).isEqualTo("PENDING");
   assertThat(prediction.referencePrice()).isEqualByComparingTo("144");
   assertThat(tracking.evaluate(symbol).evaluated()).isZero();
   assertThat(tracking.metrics(symbol)).isEmpty();

   // Test fixture simulates a forecast that matured 10 days ago. Settlement
   // must use the first observed close AFTER the due timestamp.
   Instant due=Instant.now().minus(10,ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
   jdbc.update("UPDATE prediction_records SET target_time=? WHERE id=?",Timestamp.from(due),prediction.id());
   var evaluation=tracking.evaluate(symbol);
   assertThat(evaluation.evaluated()).isEqualTo(1);
   assertThat(tracking.evaluate(symbol).evaluated()).isZero();
   var settled=tracking.get(prediction.id());
   assertThat(settled.status()).isEqualTo("EVALUATED");
   assertThat(settled.realizedTime()).isAfterOrEqualTo(due);
   assertThat(settled.absoluteErrorPct()).isPositive();
   assertThat(settled.directionCorrect()).isTrue();
   assertThat(tracking.metrics(symbol)).singleElement().satisfies(m->{
    assertThat(m.evaluated()).isEqualTo(1);
    assertThat(m.directionAccuracyPct()).isEqualTo(100);
   });
   assertThat(tracking.list(symbol,10)).hasSize(1);
   assertThatThrownBy(()->tracking.list(symbol,201)).hasMessageContaining("Limit");
  } finally {
   jdbc.update("DELETE FROM prediction_records WHERE asset_id=?",assetId);
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",assetId);
   jdbc.update("DELETE FROM assets WHERE id=?",assetId);
  }
 }

 @Test void invalidForecastRejected() {
  assertThatThrownBy(()->tracking.submit(new PredictionTrackingService.Submission("NVDA",30,
   BigDecimal.ZERO,"TEST","v1"))).hasMessageContaining("Invalid prediction");
 }
}
