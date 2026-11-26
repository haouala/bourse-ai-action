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
class FuturePriceForecastIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired FuturePriceForecastService forecasts;
 @Autowired PredictionTrackingService tracking;
 @Test void previewsFourHorizonScenariosAndExplicitlyRegistersCentralForecast(){
  String symbol="FCSTTEST";
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
   symbol,"Forecast fixture","STOCK","USD");
  Long assetId=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  try {
   double value=100;
   for(int i=0;i<920;i++){
    value*=Math.exp(.0003+.01*Math.sin(i*.72)+.008*Math.cos(i*.37));
    BigDecimal close=BigDecimal.valueOf(value);
    LocalDate day=LocalDate.now(ZoneOffset.UTC).minusDays(920-i);
    jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
     assetId,Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()),
     close,close.multiply(new BigDecimal("1.1")),close.multiply(new BigDecimal("0.9")),close);
   }
   for(int horizon:new int[]{7,30,90,180}){
    var preview=forecasts.preview(symbol,horizon);
    assertThat(preview.horizonDays()).isEqualTo(horizon);
    assertThat(preview.currency()).isEqualTo("USD");
    assertThat(preview.trainingExamples()).isGreaterThan(80);
    assertThat(preview.holdoutExamples()).isGreaterThan(20);
    assertThat(preview.pessimistic()).isLessThanOrEqualTo(preview.central());
    assertThat(preview.central()).isLessThanOrEqualTo(preview.optimistic());
    assertThat(preview.holdoutBandCoverage()).isBetween(0.0,1.0);
   }
   assertThat(tracking.list(symbol,10)).isEmpty(); // Preview GET must remain read-only.
   var saved=forecasts.issue(symbol,30);
   assertThat(saved.trackingRecord().predictedPrice()).isEqualByComparingTo(saved.forecast().central());
   assertThat(saved.trackingRecord().referencePrice()).isEqualByComparingTo(saved.forecast().referencePrice());
   assertThat(saved.trackingRecord().horizonDays()).isEqualTo(30);
   assertThat(saved.trackingRecord().status()).isEqualTo("PENDING");
   assertThat(tracking.list(symbol,10)).hasSize(1);
   assertThatThrownBy(()->forecasts.preview(symbol,31)).isInstanceOf(ResponseStatusException.class);
  } finally {
   jdbc.update("DELETE FROM prediction_records WHERE asset_id=?",assetId);
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",assetId);
   jdbc.update("DELETE FROM assets WHERE id=?",assetId);
  }
 }
}
