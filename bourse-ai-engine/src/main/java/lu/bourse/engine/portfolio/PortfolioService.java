package lu.bourse.engine.portfolio;

import lu.bourse.engine.persistence.*;
import lu.bourse.engine.service.Timeframe;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Long-only accounting in the portfolio base currency. Marks and FX are explicit, never guessed. */
@Service
public class PortfolioService {
    private final JdbcTemplate db;
    private final AssetRepository assets;
    private final Clock clock;
    public PortfolioService(JdbcTemplate db, AssetRepository assets, Clock clock) {
        this.db=db; this.assets=assets; this.clock=clock;
    }
    public record CreatePortfolio(String name, String baseCurrency, BigDecimal initialCapital, BigDecimal maxLossAmount) {}
    public record Buy(String clientOrderId, String symbol, BigDecimal quantity, BigDecimal price,
                      BigDecimal fees, BigDecimal fxToBase, Instant executedAt, String horizon) {}
    public record Trade(String clientOrderId, String side, BigDecimal quantity, BigDecimal price,
                        BigDecimal fees, BigDecimal fxToBase, Instant executedAt) {}
    public record Mark(BigDecimal price, BigDecimal fxToBase, Instant observedAt, String source) {}
    public record Risk(long portfolioId, String baseCurrency, BigDecimal cash, BigDecimal knownEquity,
                       BigDecimal loss, BigDecimal maxLossAmount, BigDecimal remainingLossBudget,
                       int missingMarks, int staleMarks, String status, Instant evaluatedAt) {}
    public record Position(long id, long assetId, String symbol, String currency, BigDecimal quantity,
                           BigDecimal averageEntryPrice, BigDecimal remainingCostBase, String horizon,
                           String signalTimeframe, String status, Instant openedAt) {}

    @Transactional
    public long create(CreatePortfolio r) {
        if(r==null || r.name()==null || r.name().isBlank() || r.name().length()>120
                || r.baseCurrency()==null || !r.baseCurrency().matches("[A-Z]{3}")) bad("Invalid portfolio");
        positive(r.initialCapital()); positive(r.maxLossAmount());
        if(r.initialCapital().scale()>8 || r.maxLossAmount().scale()>8)bad("Portfolio amounts support at most 8 decimals");
        if(r.maxLossAmount().compareTo(r.initialCapital())>0) bad("Loss budget exceeds capital");
        return SqlIds.insert(db,"INSERT INTO portfolios(name,base_currency,initial_capital,max_loss_amount) VALUES (?,?,?,?)",
                r.name(),r.baseCurrency(),r.initialCapital(),r.maxLossAmount());
    }

    @Transactional
    public long buy(long portfolioId, Buy r) {
        Map<String,Object> portfolio=lock(portfolioId);
        if(r==null || r.symbol()==null || r.horizon()==null || !Set.of("SHORT","MEDIUM","LONG").contains(r.horizon())) bad("Invalid buy or horizon");
        validateTrade(new Trade(r.clientOrderId(),"BUY",r.quantity(),r.price(),r.fees(),r.fxToBase(),r.executedAt()));
        AssetEntity asset=assets.findBySymbol(r.symbol().toUpperCase(Locale.ROOT)).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Register the asset first"));
        validateFx(portfolio,asset.getCurrency(),r.fxToBase());
        var old=existing(portfolioId,r.clientOrderId());
        if(!old.isEmpty()) {
            verifyReplay(old.getFirst(),new Trade(r.clientOrderId(),"BUY",r.quantity(),r.price(),r.fees(),r.fxToBase(),r.executedAt()));
            long previous=((Number)old.getFirst().get("position_id")).longValue();
            Position p=position(previous);
            if(p.assetId()!=asset.getId() || !p.horizon().equals(r.horizon())) conflict("Idempotency key reused for another position");
            return previous;
        }
        BigDecimal cost=money(r.price().multiply(r.quantity()).add(r.fees()).multiply(r.fxToBase()));
        if(cost.signum()==0)bad("Purchase is below accounting precision");
        if(cost.compareTo(cash(portfolioId,amount(portfolio,"initial_capital")))>0) conflict("Insufficient portfolio cash");
        String tf=switch(r.horizon()){case "SHORT"->"15m";case "MEDIUM"->"1h";default->"1d";};
        long id=SqlIds.insert(db,"INSERT INTO positions(portfolio_id,asset_id,quantity,average_entry_quote,remaining_cost_base,horizon,signal_timeframe,opened_at,status) VALUES (?,?,?,?,?,?,?,?, 'OPEN')",
                portfolioId,asset.getId(),r.quantity(),r.price(),cost,r.horizon(),tf,Timestamp.from(r.executedAt()));
        insertTrade(id,portfolioId,new Trade(r.clientOrderId(),"BUY",r.quantity(),r.price(),r.fees(),r.fxToBase(),r.executedAt()),cost.negate(),BigDecimal.ZERO);
        return id;
    }

    @Transactional
    public long recordTrade(long portfolioId,long positionId,Trade r) {
        var portfolio=lock(portfolioId); Position p=owned(portfolioId,positionId); validateTrade(r);
        validateFx(portfolio,p.currency(),r.fxToBase());
        var old=existing(portfolioId,r.clientOrderId());
        if(!old.isEmpty()) {
            verifyReplay(old.getFirst(),r);
            if(((Number)old.getFirst().get("position_id")).longValue()!=positionId) conflict("Idempotency key reused for another position");
            return ((Number)old.getFirst().get("id")).longValue();
        }
        if(!p.status().equals("OPEN")) conflict("Position is closed");
        if(r.executedAt().isBefore(p.openedAt())) bad("Trade precedes opening purchase");
        Instant latest=db.queryForObject("SELECT MAX(executed_at) FROM position_trades WHERE position_id=?",Timestamp.class,positionId).toInstant();
        if(r.executedAt().isBefore(latest)) conflict("Backdated trades require an explicit ledger correction");
        BigDecimal q,cost,entry=p.averageEntryPrice(),delta,realized=BigDecimal.ZERO;
        if(r.side().equals("SELL")) {
            if(r.quantity().compareTo(p.quantity())>0) conflict("Sell quantity exceeds holding");
            q=p.quantity().subtract(r.quantity());
            BigDecimal disposed=q.signum()==0?p.remainingCostBase():money(p.remainingCostBase().multiply(r.quantity()).divide(p.quantity(),16,RoundingMode.HALF_UP));
            cost=p.remainingCostBase().subtract(disposed);
            delta=money(r.price().multiply(r.quantity()).subtract(r.fees()).multiply(r.fxToBase()));
            if(delta.signum()<0) bad("Fees exceed sale proceeds");
            realized=delta.subtract(disposed);
        } else {
            q=p.quantity().add(r.quantity());
            BigDecimal added=money(r.price().multiply(r.quantity()).add(r.fees()).multiply(r.fxToBase()));
            if(added.signum()==0)bad("Purchase is below accounting precision");
            if(added.compareTo(cash(portfolioId,amount(portfolio,"initial_capital")))>0) conflict("Insufficient portfolio cash");
            cost=p.remainingCostBase().add(added); delta=added.negate();
            entry=p.averageEntryPrice().multiply(p.quantity()).add(r.price().multiply(r.quantity())).divide(q,10,RoundingMode.HALF_UP);
        }
        db.update("UPDATE positions SET quantity=?,remaining_cost_base=?,average_entry_quote=?,status=?,updated_time=CURRENT_TIMESTAMP WHERE id=?",
                q,cost,entry,q.signum()==0?"CLOSED":"OPEN",positionId);
        return insertTrade(positionId,portfolioId,r,delta,realized);
    }

    @Transactional
    public void mark(long portfolioId,long positionId,Mark r) {
        var portfolio=lock(portfolioId); Position p=owned(portfolioId,positionId);
        if(r==null) bad("Missing mark"); positive(r.price()); positive(r.fxToBase()); past(r.observedAt());
        if(r.observedAt().isBefore(p.openedAt()) || r.source()==null || !r.source().matches("[A-Za-z0-9._-]{1,64}")) bad("Invalid mark timestamp or source");
        if(!p.status().equals("OPEN")) conflict("Position is closed");
        validateFx(portfolio,p.currency(),r.fxToBase());
        var old=db.queryForList("SELECT * FROM position_marks WHERE position_id=?",positionId);
        if(!old.isEmpty() && SqlIds.instant(old.getFirst().get("observed_at")).isAfter(r.observedAt())) conflict("Out-of-order mark");
        if(old.isEmpty()) db.update("INSERT INTO position_marks(position_id,price_quote,fx_to_base,observed_at,source) VALUES (?,?,?,?,?)",positionId,r.price(),r.fxToBase(),Timestamp.from(r.observedAt()),r.source());
        else db.update("UPDATE position_marks SET price_quote=?,fx_to_base=?,observed_at=?,source=?,updated_time=CURRENT_TIMESTAMP WHERE position_id=?",r.price(),r.fxToBase(),Timestamp.from(r.observedAt()),r.source(),positionId);
    }

    @Transactional(readOnly=true)
    public List<Position> positions(long portfolioId) {
        requirePortfolio(portfolioId);
        return db.query(selectPosition()+" WHERE p.portfolio_id=? ORDER BY p.id",(r,i)->new Position(r.getLong("id"),r.getLong("asset_id"),r.getString("symbol"),r.getString("currency"),r.getBigDecimal("quantity"),r.getBigDecimal("average_entry_quote"),r.getBigDecimal("remaining_cost_base"),r.getString("horizon"),r.getString("signal_timeframe"),r.getString("status"),r.getTimestamp("opened_at").toInstant()),portfolioId);
    }

    @Transactional
    public Risk evaluate(long portfolioId) {
        var portfolio=lock(portfolioId); Instant now=clock.instant();
        BigDecimal initial=amount(portfolio,"initial_capital"),maxLoss=amount(portfolio,"max_loss_amount"),cash=cash(portfolioId,initial),equity=cash;
        int missing=0,stale=0;
        for(Position p:positions(portfolioId)) if(p.status().equals("OPEN")) {
            var marks=db.queryForList("SELECT * FROM position_marks WHERE position_id=?",p.id());
            if(marks.isEmpty()){missing++;continue;}
            var m=marks.getFirst(); Instant at=SqlIds.instant(m.get("observed_at"));
            // Current valuations always require recent observations, even for long-term holdings.
            if(at.isBefore(now.minus(Duration.ofMinutes(20)))) stale++;
            equity=equity.add(p.quantity().multiply(amount(m,"price_quote")).multiply(amount(m,"fx_to_base")));
        }
        equity=money(equity);
        boolean complete=missing==0 && stale==0;
        BigDecimal loss=complete?initial.subtract(equity).max(BigDecimal.ZERO):null;
        BigDecimal remaining=complete?maxLoss.subtract(loss).max(BigDecimal.ZERO):null;
        String status=missing>0?"DATA_INCOMPLETE":stale>0?"STALE_DATA":loss.compareTo(maxLoss)>=0?"LOSS_LIMIT_REACHED":"OK";
        db.update("INSERT INTO portfolio_risk_snapshots(portfolio_id,evaluated_at,status,cash_base,known_equity_base,loss_base,missing_marks,stale_marks) VALUES (?,?,?,?,?,?,?,?)",portfolioId,Timestamp.from(now),status,cash,equity,loss,missing,stale);
        boolean latched=(Boolean)portfolio.get("risk_latched");
        if(status.equals("LOSS_LIMIT_REACHED") && !latched) {
            alert(portfolioId,null,"LOSS_LIMIT","portfolio-"+portfolioId+"-loss-"+UUID.randomUUID(),"Global loss budget reached. Review and reduce exposure; no broker order was sent.",now);
            db.update("UPDATE portfolios SET risk_latched=TRUE,updated_time=CURRENT_TIMESTAMP WHERE id=?",portfolioId);
        } else if(status.equals("OK") && latched) db.update("UPDATE portfolios SET risk_latched=FALSE,updated_time=CURRENT_TIMESTAMP WHERE id=?",portfolioId);
        return new Risk(portfolioId,(String)portfolio.get("base_currency"),cash,equity,loss,maxLoss,remaining,missing,stale,status,now);
    }

    /** Chart overlays are scenarios, not executable orders or forecasts. */
    @Transactional
    public Map<String,Object> plan(long portfolioId,long positionId) {
        Risk risk=evaluate(portfolioId); Position p=owned(portfolioId,positionId);
        var out=new LinkedHashMap<String,Object>(); out.put("position",p);out.put("risk",risk);
        out.put("method","GLOBAL_BUDGET_SCENARIO_V1");out.put("executableOrder",false);
        if(!risk.status().equals("OK") || !p.status().equals("OPEN")){out.put("status","INSUFFICIENT_FRESH_DATA_OR_RISK_LIMIT");return out;}
        var marks=db.queryForList("SELECT * FROM position_marks WHERE position_id=?",positionId);
        if(marks.isEmpty()){out.put("status","NO_MARK");return out;}
        BigDecimal totalMarket=risk.knownEquity().subtract(risk.cash());
        BigDecimal ratio=totalMarket.signum()>0?risk.remainingLossBudget().divide(totalMarket,12,RoundingMode.HALF_UP).min(BigDecimal.ONE):BigDecimal.ZERO;
        BigDecimal price=amount(marks.getFirst(),"price_quote");
        BigDecimal stop=price.multiply(BigDecimal.ONE.subtract(ratio)).max(BigDecimal.ZERO).setScale(10,RoundingMode.HALF_UP);
        out.put("status","ILLUSTRATIVE_SCENARIO");out.put("observedPrice",price);out.put("proposedProtection",stop);
        out.put("targetPrice",p.averageEntryPrice().add(p.averageEntryPrice().subtract(stop).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(2))));
        out.put("targetRationale","Illustrative 2R objective, not a calibrated forecast; fees, liquidity and horizon need validation");
        return out;
    }

    public List<Map<String,Object>> alerts(long portfolioId) {
        requirePortfolio(portfolioId);
        return db.queryForList("SELECT * FROM trade_alerts WHERE portfolio_id=? ORDER BY id DESC LIMIT 200",portfolioId);
    }
    @Transactional public void acknowledge(long portfolioId,long alertId) {
        requirePortfolio(portfolioId);
        if(db.update("UPDATE trade_alerts SET status='ACKNOWLEDGED',updated_time=CURRENT_TIMESTAMP WHERE id=? AND portfolio_id=?",alertId,portfolioId)==0) throw error(HttpStatus.NOT_FOUND,"Unknown alert");
    }
    public void alert(long portfolioId,Long positionId,String type,String key,String message,Instant dataTime) {
        if(db.queryForObject("SELECT COUNT(*) FROM trade_alerts WHERE event_key=?",Integer.class,key)>0) return;
        db.update("INSERT INTO trade_alerts(portfolio_id,position_id,event_key,alert_type,message,data_time) VALUES (?,?,?,?,?,?)",portfolioId,positionId,key,type,message,Timestamp.from(dataTime));
    }
    public List<Long> portfolioIds(){return db.queryForList("SELECT id FROM portfolios ORDER BY id",Long.class);}
    private Position position(long id) {
        var ids=db.queryForList("SELECT portfolio_id FROM positions WHERE id=?",Long.class,id);
        if(ids.isEmpty()) throw error(HttpStatus.NOT_FOUND,"Unknown position");
        return positions(ids.getFirst()).stream().filter(p->p.id()==id).findFirst().orElseThrow();
    }
    public Position owned(long portfolioId,long id) {Position p=position(id);if(db.queryForObject("SELECT portfolio_id FROM positions WHERE id=?",Long.class,id)!=portfolioId) throw error(HttpStatus.NOT_FOUND,"Unknown position");return p;}
    private String selectPosition(){return "SELECT p.*,a.symbol,a.currency FROM positions p JOIN assets a ON a.id=p.asset_id";}
    private Map<String,Object> lock(long id) {var rows=db.queryForList("SELECT * FROM portfolios WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Unknown portfolio");return rows.getFirst();}
    private void requirePortfolio(long id){if(db.queryForObject("SELECT COUNT(*) FROM portfolios WHERE id=?",Integer.class,id)==0)throw error(HttpStatus.NOT_FOUND,"Unknown portfolio");}
    private BigDecimal cash(long id,BigDecimal initial){return initial.add(db.queryForObject("SELECT COALESCE(SUM(cash_delta_base),0) FROM position_trades WHERE portfolio_id=?",BigDecimal.class,id));}
    private List<Map<String,Object>> existing(long portfolioId,String key){return db.queryForList("SELECT * FROM position_trades WHERE portfolio_id=? AND client_order_id=?",portfolioId,key);}
    private long insertTrade(long positionId,long portfolioId,Trade r,BigDecimal delta,BigDecimal realized){
        return SqlIds.insert(db,"INSERT INTO position_trades(position_id,portfolio_id,client_order_id,side,quantity,price_quote,fees_quote,fx_to_base,cash_delta_base,realized_pnl_base,executed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",positionId,portfolioId,r.clientOrderId(),r.side(),r.quantity(),r.price(),r.fees(),r.fxToBase(),delta,realized,Timestamp.from(r.executedAt()));
    }
    private void verifyReplay(Map<String,Object> old,Trade r){
        if(!old.get("side").equals(r.side()) || amount(old,"quantity").compareTo(r.quantity())!=0 || amount(old,"price_quote").compareTo(r.price())!=0 || amount(old,"fees_quote").compareTo(r.fees())!=0 || amount(old,"fx_to_base").compareTo(r.fxToBase())!=0 || !SqlIds.instant(old.get("executed_at")).equals(r.executedAt())) conflict("Idempotency key reused with different trade");
    }
    private void validateTrade(Trade r){
        if(r==null || r.clientOrderId()==null || !r.clientOrderId().matches("[A-Za-z0-9._-]{1,80}") || r.side()==null || !Set.of("BUY","SELL").contains(r.side())) bad("Invalid trade");
        positive(r.quantity());positive(r.price());positive(r.fxToBase());nonnegative(r.fees());past(r.executedAt());
        if(r.quantity().scale()>8) bad("Quantity supports at most 8 decimals");
    }
    private void validateFx(Map<String,Object> portfolio,String currency,BigDecimal fx){if(portfolio.get("base_currency").equals(currency) && fx.compareTo(BigDecimal.ONE)!=0) bad("Same-currency FX must equal 1");}
    private void positive(BigDecimal x){nonnegative(x);if(x.signum()==0)bad("Value must be positive");}
    private void nonnegative(BigDecimal x){if(x==null || x.signum()<0 || x.scale()>10 || x.precision()>24)bad("Invalid numeric value");}
    private void past(Instant x){if(x==null || x.isAfter(clock.instant()) || x.isBefore(Instant.parse("1970-01-01T00:00:00Z")) || x.getNano()%1000!=0)bad("Invalid time; maximum timestamp precision is microseconds");}
    private static BigDecimal amount(Map<String,Object> row,String key){return (BigDecimal)row.get(key);}
    private static BigDecimal money(BigDecimal x){return x.setScale(8,RoundingMode.HALF_UP);}
    private static void bad(String text){throw error(HttpStatus.BAD_REQUEST,text);}
    private static void conflict(String text){throw error(HttpStatus.CONFLICT,text);}
    private static ResponseStatusException error(HttpStatus status,String text){return new ResponseStatusException(status,text);}
}
