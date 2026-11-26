package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class MarketContagionIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired StockRelationshipService relationships;
 @Autowired MarketContagionService contagion;
 @Test void analysesOnlyActiveLinkedPeerWithAlignedDailySeries(){
  for(String symbol:new String[]{"CONROOT","CONPEER","CONOTHER"}){
   jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
    symbol,"Contagion "+symbol,"STOCK","USD");
  }
  try {
   var edge=relationships.add(new StockRelationshipService.Submission("CONROOT","CONPEER",
    "SUPPLIES_TO","MEDIUM","https://example.org/verified-test-fixture",
    "Integration test fixture",LocalDate.now().minusDays(2),null,null));
   double rootClose=100,peerClose=100,unlinkedClose=100;
   for(int i=0;i<105;i++){
    double rootReturn=.002+Math.sin(i*2.41)*.012;
    double peerReturn=.002+Math.sin((i-1)*2.41)*.012;
    rootClose*=Math.exp(rootReturn);
    peerClose*=Math.exp(peerReturn);
    unlinkedClose*=Math.exp(Math.cos(i)*.005);
    Instant time=LocalDate.now(ZoneOffset.UTC).minusDays(110-i).atStartOfDay(ZoneOffset.UTC).toInstant();
    store("CONROOT",time,rootClose);
    store("CONPEER",time,peerClose);
    store("CONOTHER",time,unlinkedClose);
   }
   var result=contagion.analyze("CONROOT",150,10);
   assertThat(result.comparisons()).hasSize(1);
   var pair=result.comparisons().get(0);
   assertThat(pair.target()).isEqualTo("CONPEER");
   assertThat(pair.leadingCorrelations()).hasSize(4);
   assertThat(pair.leadingCorrelations().get(1).coefficient()).isGreaterThan(.9);
   assertThat(pair.leadingCorrelations().get(1).observations()).isGreaterThan(80);
   assertThat(result.disclaimer()).contains("not evidence of causation");
   assertThatThrownBy(()->contagion.analyze("CONROOT",20,10))
    .isInstanceOf(ResponseStatusException.class);
   relationships.retract(edge.id());
   assertThat(contagion.analyze("CONROOT",150,10).comparisons()).isEmpty();
  } finally {
   jdbc.update("DELETE FROM company_relationships WHERE from_asset_id IN (SELECT id FROM assets WHERE symbol LIKE 'CON%') OR to_asset_id IN (SELECT id FROM assets WHERE symbol LIKE 'CON%')");
   jdbc.update("DELETE FROM market_candles WHERE asset_id IN (SELECT id FROM assets WHERE symbol LIKE 'CON%')");
   jdbc.update("DELETE FROM assets WHERE symbol IN ('CONROOT','CONPEER','CONOTHER')");
  }
 }
 private void store(String symbol,Instant time,double close){
  Long id=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  BigDecimal c=BigDecimal.valueOf(close);
  jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
   id,Timestamp.from(time),c,c.multiply(new BigDecimal("1.05")),c.multiply(new BigDecimal("0.95")),c);
 }
}
