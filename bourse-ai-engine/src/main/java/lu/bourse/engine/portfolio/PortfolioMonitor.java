package lu.bourse.engine.portfolio;

import lu.bourse.engine.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class PortfolioMonitor {
    private final PortfolioService portfolios; private final JdbcTemplate db; private final Clock clock;
    public PortfolioMonitor(PortfolioService portfolios,JdbcTemplate db,Clock clock){this.portfolios=portfolios;this.db=db;this.clock=clock;}
    @Transactional public PortfolioService.Risk evaluate(long portfolioId){
        // Serialize monitor instances and trade/mark writes for the same portfolio.
        db.queryForList("SELECT id FROM portfolios WHERE id=? FOR UPDATE",portfolioId);
        var positions=portfolios.positions(portfolioId);Instant now=clock.instant();
        String base=db.queryForObject("SELECT base_currency FROM portfolios WHERE id=?",String.class,portfolioId);
        for(var p:positions)if(p.status().equals("OPEN") && p.currency().equals(base)){
            var bars=db.query("SELECT open_time,close_price,provider FROM market_candles WHERE asset_id=? AND timeframe='15m' AND open_time<=? ORDER BY open_time DESC LIMIT 1",
                    (r,i)->new Object[]{r.getTimestamp(1).toInstant().plusSeconds(900),r.getBigDecimal(2),r.getString(3)},p.assetId(),Timestamp.from(now.minusSeconds(900)));
            if(!bars.isEmpty()){
                var bar=bars.getFirst();Instant at=(Instant)bar[0];
                var old=db.query("SELECT observed_at FROM position_marks WHERE position_id=?",(r,i)->r.getTimestamp(1).toInstant(),p.id());
                if(!at.isBefore(p.openedAt()) && (old.isEmpty() || at.isAfter(old.getFirst())))
                    portfolios.mark(portfolioId,p.id(),new PortfolioService.Mark((BigDecimal)bar[1],BigDecimal.ONE,at,(String)bar[2]));
            }
        }
        var risk=portfolios.evaluate(portfolioId);
        if(!risk.status().equals("OK"))return risk;
        for(var p:positions)if(p.status().equals("OPEN")){
            Timeframe tf=Timeframe.parse(p.signalTimeframe());
            var bars=db.query("SELECT open_time,close_price FROM market_candles WHERE asset_id=? AND timeframe=? AND open_time<=? ORDER BY open_time DESC LIMIT 250",
                    (r,i)->new Bar(r.getTimestamp(1).toInstant(),r.getBigDecimal(2)),p.assetId(),tf.code(),Timestamp.from(now.minus(tf.duration())));
            if(bars.size()<36)continue;
            Collections.reverse(bars);Bar last=bars.getLast();Instant closed=tf.closeTime(last.time());
            if(closed.isBefore(p.openedAt()) || closed.isBefore(now.minus(tf.duration().multipliedBy(2))))continue;
            var prices=bars.stream().map(Bar::close).toList();
            var previous=FinancialIndicators.calculate(prices.subList(0,prices.size()-1));
            var current=FinancialIndicators.calculate(prices);
            if(previous.macdHistogram().signum()>=0 && current.macdHistogram().signum()<0){
                String key="position-"+p.id()+"-macd-"+tf.code()+"-"+closed.getEpochSecond();
                portfolios.alert(portfolioId,p.id(),"MACD_REVIEW",key,"Bearish MACD crossing on "+tf.code()+" for "+p.horizon()+" holding; review only, not an automatic sell order.",closed);
            }
        }
        return risk;
    }
    public List<Long> portfolioIds(){return portfolios.portfolioIds();}
    private record Bar(Instant time,BigDecimal close){}
}
