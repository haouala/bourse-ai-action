package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Recent native provider bars. A 5,000-bar page is not advertised as ten years of intraday history. */
@Service
public class TwelveDataSeriesService {
    private final ProviderHttp http; private final HistoryService history; private final ImportJobService jobs; private final Clock clock;private final lu.bourse.engine.calendar.MarketCalendarService calendars;
    private final JdbcTemplate jdbc;
    @Value("${market.twelve-data.base-url}") private String base;
    @Value("${market.twelve-data.api-key:}") private String key;
    public TwelveDataSeriesService(ProviderHttp http,HistoryService history,ImportJobService jobs,Clock clock,lu.bourse.engine.calendar.MarketCalendarService calendars, JdbcTemplate jdbc){this.jdbc=jdbc;this.http=http;this.history=history;this.jobs=jobs;this.clock=clock;this.calendars=calendars;}
    public HistoryService.ImportResult sync(Map<String,Object> asset,String code){
        Timeframe tf=Timeframe.parse(code);
        if(key.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set TWELVE_DATA_API_KEY");
        String symbol=(String)asset.get("symbol");long job=jobs.start(symbol,"TWELVE_DATA",code+"_OHLCV");
        try{
            long assetId = ((Number) asset.get("id")).longValue();
            Timestamp latest = jdbc.queryForObject(
                    "SELECT MAX(open_time) FROM market_candles WHERE asset_id=? AND timeframe=? AND provider='TWELVE_DATA'",
                    Timestamp.class, assetId, code);
            Instant previous = latest == null ? null : latest.toInstant();
            var request=UriComponentsBuilder.fromUriString(base+"/time_series").queryParam("symbol",asset.get("provider_symbol"))
                    .queryParam("interval",tf.twelveCode()).queryParam("timezone","UTC").queryParam("outputsize",5000).queryParam("apikey",key);
            if (previous != null) request.queryParam("start_date", startDate(previous, code));
            String url = request.build().encode().toUriString();
            ProviderOperation operation = tf == Timeframe.parse("1d")
                    ? ProviderOperation.TWELVE_DAILY : ProviderOperation.TWELVE_INTRADAY;
            JsonNode doc=http.get(operation,url,null),values=doc.path("values");
            if(!values.isArray() || values.isEmpty())throw unavailable();
            String currency=doc.path("meta").path("currency").asText("").toUpperCase(Locale.ROOT);
            if(!currency.equals(asset.get("currency")))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Provider currency is absent or differs from registered asset");
            List<HistoryService.PriceBar> bars=new ArrayList<>();Instant now=clock.instant();
            for(JsonNode v:values){
                String dt=v.path("datetime").asText();
                Instant at=code.equals("1d")?LocalDate.parse(dt).atStartOfDay(ZoneOffset.UTC).toInstant():LocalDateTime.parse(dt,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
                if(calendars.barClose(assetId,at,tf.duration()).isAfter(now))continue;
                // No fabricated zero volume: unavailable crypto volume must remain an explicit error.
                bars.add(new HistoryService.PriceBar(at,number(v,"open"),number(v,"high"),number(v,"low"),number(v,"close"),number(v,"volume")));
            }
            if (bars.isEmpty()) {
                jobs.end(job, 0, null);
                return new HistoryService.ImportResult(symbol, 0, 0);
            }
            var result=history.importBars(new HistoryService.ImportRequest(symbol,(String)asset.get("name"),(String)asset.get("asset_type"),currency,code,"TWELVE_DATA",bars));
            jobs.end(job,result.inserted(),null);return result;
        }catch(ResponseStatusException|lu.bourse.engine.provider.quota.QuotaExceededException e){jobs.end(job,0,"IMPORT_REJECTED");throw e;}
        catch(Exception e){jobs.end(job,0,"INVALID_PROVIDER_SERIES");throw unavailable();}
    }
    static String startDate(Instant latest, String timeframe) {
        // Revisit recent bars to fill delayed observations without rewriting existing candles.
        if ("1d".equals(timeframe)) return latest.atZone(ZoneOffset.UTC).toLocalDate().minusDays(7).toString();
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(latest.minus(Duration.ofDays(2)));
    }

    private static BigDecimal number(JsonNode v,String field){if(v.path(field).isMissingNode() || v.path(field).isNull())throw unavailable();return new BigDecimal(v.path(field).asText());}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid or incomplete provider series");}
}
