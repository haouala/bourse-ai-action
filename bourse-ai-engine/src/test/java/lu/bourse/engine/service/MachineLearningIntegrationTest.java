package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class MachineLearningIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired MachineLearningService ml;

 @Test void trainPersistsRealValidationMetricsAndReturnsHistory(){
  String ticker="MLFIXTURE";
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
   ticker,"Machine learning fixture","STOCK","USD");
  Long id=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,ticker);
  try{
   double price=100;
   for(int i=0;i<350;i++){
    price*=Math.exp(.001+.015*Math.sin(i*1.47)+.005*Math.cos(i*2.22));
    BigDecimal c=BigDecimal.valueOf(price);
    LocalDate day=LocalDate.now(ZoneOffset.UTC).minusDays(352-i);
    jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
     id,Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()),
     c,c.multiply(new BigDecimal("1.05")),c.multiply(new BigDecimal("0.95")),c);
   }
   var training=ml.train(ticker,10);
   assertThat(training.validation().folds()).hasSize(3);
   assertThat(training.run().testSamples()).isGreaterThan(20);
   assertThat(training.run().brierScore()).isBetween(0.0,1.0);
   assertThat(training.run().status()).isIn("RESEARCH_ONLY","BASELINE_NOT_BEATEN");
   assertThat(ml.get(training.run().id()).symbol()).isEqualTo(ticker);
   assertThat(ml.history(ticker,10)).hasSize(1);
   assertThatThrownBy(()->ml.train(ticker,30)).isInstanceOf(ResponseStatusException.class);
   assertThatThrownBy(()->ml.history(ticker,200)).isInstanceOf(ResponseStatusException.class);
  } finally {
   jdbc.update("DELETE FROM ml_training_runs WHERE asset_id=?",id);
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",id);
   jdbc.update("DELETE FROM assets WHERE id=?",id);
  }
 }
}
