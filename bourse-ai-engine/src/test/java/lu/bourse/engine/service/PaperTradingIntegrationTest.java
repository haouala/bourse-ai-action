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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PaperTradingIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired PaperTradingService paper;

 @Test void buysAndSellsOnlyVirtualHoldingsWithIdempotentClientOrders(){
  String symbol="PAPERSIM";
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",
   symbol,"Simulation stock","STOCK","USD");
  Long asset=jdbc.queryForObject("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);
  Long accountId=null;
  try{
   for(int i=0;i<45;i++){
    BigDecimal close=new BigDecimal("100.00");
    Instant day=LocalDate.now(ZoneOffset.UTC).minusDays(46-i).atStartOfDay(ZoneOffset.UTC).toInstant();
    jdbc.update("INSERT INTO market_candles(asset_id,timeframe,open_time,open_price,high_price,low_price,close_price,volume,provider) VALUES (?,'1d',?,?,?,?,?,1000,'TEST')",
     asset,Timestamp.from(day),close,close.add(BigDecimal.ONE),close.subtract(BigDecimal.ONE),close);
   }
   var acct=paper.create("simulation_fixture_987",new BigDecimal("1000"));
   accountId=acct.id();
   var first=paper.execute(new PaperTradingService.Order(accountId,"buy-1",symbol,"BUY",new BigDecimal("2")));
   assertThat(first.simulatedPrice()).isEqualByComparingTo("100");
   assertThat(first.simulatedFee()).isEqualByComparingTo("0.2");
   assertThat(paper.execute(new PaperTradingService.Order(accountId,"buy-1",symbol,"BUY",new BigDecimal("2"))).id())
    .isEqualTo(first.id());
   assertThat(paper.history(accountId,10)).hasSize(1);
   assertThat(paper.account(accountId).cashBalance()).isEqualByComparingTo("799.8");
   var before=paper.snapshot(accountId);
   assertThat(before.positions()).hasSize(1);
   assertThat(before.markedEquity()).isEqualByComparingTo("999.8");
   final long aid=accountId;
   assertThatThrownBy(()->paper.execute(new PaperTradingService.Order(aid,"oversell",symbol,"SELL",new BigDecimal("3"))))
    .isInstanceOf(ResponseStatusException.class);
   assertThatThrownBy(()->paper.execute(new PaperTradingService.Order(aid,"overspend",symbol,"BUY",new BigDecimal("100"))))
    .isInstanceOf(ResponseStatusException.class);
   paper.execute(new PaperTradingService.Order(accountId,"sell-1",symbol,"SELL",BigDecimal.ONE));
   assertThat(paper.snapshot(accountId).positions().get(0).quantity()).isEqualByComparingTo("1");
   assertThat(paper.history(accountId,10)).hasSize(2);
  }finally{
   if(accountId!=null){
    jdbc.update("DELETE FROM paper_trades WHERE account_id=?",accountId);
    jdbc.update("DELETE FROM paper_accounts WHERE id=?",accountId);
   }
   jdbc.update("DELETE FROM market_candles WHERE asset_id=?",asset);
   jdbc.update("DELETE FROM assets WHERE id=?",asset);
  }
 }
}
