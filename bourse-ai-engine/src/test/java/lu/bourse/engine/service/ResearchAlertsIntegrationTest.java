package lu.bourse.engine.service;
import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ResearchAlertsIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired ResearchAlertsService alerts;
 @Test void recordsAnEventExactlyOnceForAnObservedCandle(){
  String sym="ALERTFIX";jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
   sym,"Alert fixture","STOCK","USD");
  Long asset=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,sym);
  Long rule=null;
  try{
   Instant day=LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
   jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
    asset,Timestamp.from(day),new BigDecimal("120"),new BigDecimal("125"),new BigDecimal("115"),new BigDecimal("120"));
   rule=alerts.create(sym,"PRICE_ABOVE",new BigDecimal("110")).id();
   assertThat(alerts.check(sym).triggered()).isEqualTo(1);
   assertThat(alerts.check(sym).triggered()).isZero();
   assertThat(alerts.events(sym,10)).hasSize(1);
   alerts.disable(rule);
   assertThat(alerts.check(sym).checked()).isZero();
  } finally {
   if(rule!=null){
    jdbc.update("DELETE FROM research_alert_events WHERE rule_id=?",rule);
    jdbc.update("DELETE FROM research_alert_rules WHERE id=?",rule);
   }
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",asset);
   jdbc.update("DELETE FROM assets WHERE id=?",asset);
  }
 }
}
