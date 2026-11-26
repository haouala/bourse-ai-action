package lu.bourse.engine.api;

import lu.bourse.engine.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api/v1/charts")
public class ChartController {
    private final ClosedMarketHistory history;private final Clock clock;
    public ChartController(ClosedMarketHistory history,Clock clock){this.history=history;this.clock=clock;}
    public record Chart(String symbol,String timeframe,String currency,List<CandlestickPatterns.Bar> candles,FinancialIndicators.Result latestIndicators,String warning,CandlestickPatterns.Report patternAnalysis){}
    @GetMapping("/{symbol}")
    public Chart chart(@PathVariable String symbol,@RequestParam(defaultValue="15m")String timeframe,@RequestParam(defaultValue="250")int limit){
        if(limit<1||limit>2000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid chart query");
        var h=history.load(symbol,timeframe,limit,clock.instant());var bars=h.bars();
        var indicators=bars.size()>=35?FinancialIndicators.calculate(bars.stream().map(CandlestickPatterns.Bar::close).toList()):null;
        return new Chart(h.symbol(),h.timeframe(),h.currency(),bars,indicators,
                "Raw imported data; latency, corporate actions and execution prices must be verified",CandlestickPatterns.analyze(bars));
    }
}
