package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PortfolioRiskIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired PortfolioRiskService risk;
 @Test void calculatesAuditableBasketButBlocksUnsupportedCurrencyMix(){
  for(String ticker:List.of("PRISKA","PRISKB","PRISKEUR"))
   jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
    ticker,ticker,"STOCK",ticker.equals("PRISKEUR")?"EUR":"USD");
  try{
   for(String ticker:List.of("PRISKA","PRISKB","PRISKEUR")){
    Long id=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,ticker);
    double price=ticker.equals("PRISKB")?80:100;
    for(int i=0;i<100;i++){
     price*=Math.exp(.0003+.01*Math.sin(i*.71+(ticker.equals("PRISKB")?1:0)));
     BigDecimal close=BigDecimal.valueOf(price);
     Instant day=LocalDate.now(ZoneOffset.UTC).minusDays(100-i).atStartOfDay(ZoneOffset.UTC).toInstant();
     jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
      id,Timestamp.from(day),close,close.multiply(new BigDecimal("1.05")),
      close.multiply(new BigDecimal("0.95")),close);
    }
   }
   var response=risk.analyze(new PortfolioRiskService.Request(List.of(
    new PortfolioRiskService.Holding("PRISKA",new BigDecimal("2")),
    new PortfolioRiskService.Holding("PRISKB",new BigDecimal("1.5")))));
   assertThat(response.currency()).isEqualTo("USD");
   assertThat(response.positions()).hasSize(2);
   assertThat(response.historicalRisk().commonDates()).isEqualTo(61);
   assertThat(response.totalValue()).isPositive();
   assertThatThrownBy(()->risk.analyze(new PortfolioRiskService.Request(List.of(
    new PortfolioRiskService.Holding("PRISKA",BigDecimal.ONE),
    new PortfolioRiskService.Holding("PRISKEUR",BigDecimal.ONE)))))
     .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Mixed currencies");
  }finally{
   jdbc.update("DELETE FROM market_candles WHERE asset_id IN (SELECT id FROM assets WHERE symbol LIKE 'PRISK%')");
   jdbc.update("DELETE FROM assets WHERE symbol IN ('PRISKA','PRISKB','PRISKEUR')");
  }
 }
}
