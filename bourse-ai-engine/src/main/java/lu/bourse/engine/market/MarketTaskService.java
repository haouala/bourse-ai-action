package lu.bourse.engine.market;

import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.provider.*;
import lu.bourse.engine.provider.quota.QuotaExceededException;
import lu.bourse.engine.provider.quota.MarketProvider;
import lu.bourse.engine.provider.quota.ProviderQuotaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class MarketTaskService {
    private final JdbcTemplate db; private final TwelveDataSeriesService series; private final Clock clock;
    private final TransactionTemplate transaction;
    private final ProviderQuotaService quotas;
    private final MarketAnalysisService analyses;
    private final LocalCandleAggregationService aggregation;
    public MarketTaskService(JdbcTemplate db,TwelveDataSeriesService series,Clock clock,PlatformTransactionManager manager,LocalCandleAggregationService aggregation,ProviderQuotaService quotas,MarketAnalysisService analyses){this.analyses=analyses;this.quotas=quotas;this.aggregation=aggregation;this.db=db;this.series=series;this.clock=clock;transaction=new TransactionTemplate(manager);}
    public List<Map<String,Object>> list(){return db.queryForList("SELECT t.*,a.symbol FROM market_sync_tasks t JOIN assets a ON a.id=t.asset_id ORDER BY t.id DESC LIMIT 200");}
    /** Claim under a short DB lock; never keep the transaction open during remote HTTP. */
    public Map<String,Object> runNext(){
        Instant now=clock.instant();
        boolean allowProvider = quotas.usage(MarketProvider.TWELVE_DATA).canCall(clock.instant());
        Map<String,Object> task=transaction.execute(status->{
            db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE", Integer.class);
            var rows=db.queryForList("""
                    SELECT t.* FROM market_sync_tasks t JOIN watchlist w ON w.asset_id=t.asset_id
                    WHERE w.enabled=TRUE AND (? OR t.timeframe IN ('30m','1h'))
                      AND ((t.status IN ('PENDING','DEFERRED') AND t.next_attempt_at<=?)
                        OR (t.status='RUNNING' AND t.lease_until<=?))
                      AND NOT EXISTS (
                        SELECT 1 FROM market_sync_tasks running
                        WHERE running.asset_id=t.asset_id AND running.timeframe=t.timeframe
                          AND running.status='RUNNING' AND running.lease_until>?
                      )
                      AND (t.timeframe NOT IN ('30m','1h') OR NOT EXISTS (
                        SELECT 1 FROM market_sync_tasks source
                        WHERE source.asset_id=t.asset_id AND source.timeframe='15m'
                          AND source.slot_time=t.slot_time AND source.status IN ('PENDING','DEFERRED','RUNNING')
                      ))
                    ORDER BY t.next_attempt_at,t.id LIMIT 1 FOR UPDATE
                    """,allowProvider,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
            if(rows.isEmpty())return null;
            var row=rows.getFirst();
            db.update("UPDATE market_sync_tasks SET status='RUNNING',lease_until=?,attempts=attempts+1,updated_time=CURRENT_TIMESTAMP WHERE id=?",Timestamp.from(now.plusSeconds(600)),row.get("id"));
            return row;
        });
        if(task==null)return Map.of("status",allowProvider?"IDLE":"PROVIDER_QUOTA_PAUSED");
        long id=((Number)task.get("id")).longValue();
        try{
            var asset=db.queryForList("SELECT a.*,w.provider_symbol FROM assets a JOIN watchlist w ON a.id=w.asset_id WHERE a.id=?",task.get("asset_id")).getFirst();
            String timeframe=(String)task.get("timeframe");
            var result=Set.of("30m","1h").contains(timeframe)?aggregation.sync(asset,timeframe):series.sync(asset,timeframe);
            var analysis = analyses.refresh(((Number) asset.get("id")).longValue(), result.symbol(), timeframe);
            finish(id,"SUCCEEDED",result.inserted(),null,now);
            return Map.of("taskId",id,"status","SUCCEEDED","inserted",result.inserted(),"skipped",result.skipped(),"analysis",analysis);
        }catch(QuotaExceededException e){
            Instant retry=e.retryAt()==null?now.plusSeconds(86400):e.retryAt().plusSeconds(1);
            finish(id,"DEFERRED",0,"LOCAL_QUOTA",retry);
            return Map.of("taskId",id,"status","DEFERRED","reason","LOCAL_QUOTA");
        }catch(ResponseStatusException e){
            int code=e.getStatusCode().value();
            boolean retry=code==429 || code==502;
            int attempts=((Number)task.get("attempts")).intValue()+1;
            String state=retry && attempts<3?"DEFERRED":"FAILED";
            finish(id,state,0,"HTTP_"+code,now.plusSeconds(code==429?86400:900));
            return Map.of("taskId",id,"status",state,"reason","HTTP_"+code);
        }catch(Exception e){
            finish(id,"FAILED",0,"LOCAL_FAILURE",now);
            return Map.of("taskId",id,"status","FAILED","reason","LOCAL_FAILURE");
        }
    }
    private void finish(long id,String state,int imported,String error,Instant next){db.update("UPDATE market_sync_tasks SET status=?,imported_count=?,error_code=?,next_attempt_at=?,lease_until=NULL,updated_time=CURRENT_TIMESTAMP WHERE id=?",state,imported,error,Timestamp.from(next),id);}
}
