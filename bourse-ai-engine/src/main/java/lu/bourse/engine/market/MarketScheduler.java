package lu.bourse.engine.market;
import lu.bourse.engine.portfolio.PortfolioMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketScheduler {
    private final WatchlistService watchlist;private final MarketTaskService tasks;private final PortfolioMonitor monitor;
    @Value("${market.scheduler.enabled:false}") private boolean enabled;
    public MarketScheduler(WatchlistService watchlist,MarketTaskService tasks,PortfolioMonitor monitor){this.watchlist=watchlist;this.tasks=tasks;this.monitor=monitor;}
    @Scheduled(cron="0 */15 * * * *",zone="UTC") public void enqueue(){if(enabled)watchlist.enqueue();}
    @Scheduled(fixedDelayString="${market.scheduler.worker-delay-ms:10000}") public void work(){if(enabled)tasks.runNext();}
    @Scheduled(fixedDelayString="${market.scheduler.monitor-delay-ms:60000}") public void evaluate(){if(enabled)for(long id:monitor.portfolioIds())monitor.evaluate(id);}
}
