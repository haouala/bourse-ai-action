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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class QualityBacktestIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired DataQualityService quality;
 @Autowired HistoricalBacktestService backtest;
 @Test void flagsSourceDisagreementAndRunsDeterministicCostAwareBacktest(){
  String symbol="BTQUALITY";
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",symbol,"Backtest fixture","STOCK","USD");
  Long id=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  try {
   for(int i=0;i<45;i++){
    Instant date=Instant.now().minus(50-i,ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
    BigDecimal close=BigDecimal.valueOf(100+i);
    jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'PRIMARY')",
     id,Timestamp.from(date),close,close.add(BigDecimal.TEN),close.subtract(BigDecimal.TEN),close);
   }
   Instant matched=Instant.now().minus(50,ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
   jdbc.update("INSERT INTO provider_price_observations(asset_id,provider,provider_symbol,timeframe,open_time,open_price,high_price,low_price,close_price,volume,currency,price_adjustment) VALUES (?,'SECONDARY',?,'1d',?,?,?,?,?,1000,'USD','UNSPECIFIED')",
    id,symbol,Timestamp.from(matched),new BigDecimal("100"),new BigDecimal("120"),new BigDecimal("90"),new BigDecimal("110"));
   var report=quality.check(symbol,"1d");
   assertThat(report.independentProviderMatches()).isEqualTo(1);
   assertThat(report.issues()).anyMatch(issue->issue.code().equals("CROSS_PROVIDER_DIVERGENCE"));
   var result=backtest.run(symbol,5,20,10);
   assertThat(result.curve()).hasSize(45);
   assertThat(result.trades()).isPositive();
   assertThat(result.finalEquity()).isPositive();
   assertThatThrownBy(()->backtest.run(symbol,20,5,10)).hasMessageContaining("Invalid");
  } finally {
   jdbc.update("DELETE FROM provider_price_observations WHERE asset_id=?",id);
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",id);
   jdbc.update("DELETE FROM assets WHERE id=?",id);
  }
 }
}
