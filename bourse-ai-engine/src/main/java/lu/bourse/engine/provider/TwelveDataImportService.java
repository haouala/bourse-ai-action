package lu.bourse.engine.provider;
import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.service.HistoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
@Service
public class TwelveDataImportService {
 private final JdbcTemplate jdbc;
 private final Clock clock;
 private final ProviderHttp http; private final HistoryService history; private final ImportJobService jobs;
 @Value("${market.twelve-data.base-url}") private String base;
 @Value("${market.twelve-data.api-key:}") private String key;
 public TwelveDataImportService(ProviderHttp http,HistoryService history,ImportJobService jobs,JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;this.http=http;this.history=history;this.jobs=jobs;}
 public HistoryService.ImportResult sync(String rawSymbol,int years){
  if(rawSymbol==null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || years<1 || years>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or years");
  if(key.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set TWELVE_DATA_API_KEY in environment");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT); long job=jobs.start(symbol,"TWELVE_DATA","DAILY_OHLCV");
  try{
   LocalDate today=LocalDate.now(ZoneOffset.UTC);
   JsonNode doc=daily(symbol,today.minusYears(years),today,5000),values=doc.path("values");
   if(!values.isArray() || values.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"No daily candles returned; check subscription/symbol");
   String currency=doc.path("meta").path("currency").asText("USD").toUpperCase(Locale.ROOT);
   if(!currency.matches("[A-Z]{3}"))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid currency returned");
   List<HistoryService.PriceBar> bars=new ArrayList<>();
   for(JsonNode v:values){
    LocalDate day=LocalDate.parse(v.path("datetime").asText());
    if(!day.isBefore(today))continue; // don't store today's potentially incomplete daily bar
    bars.add(new HistoryService.PriceBar(day.atStartOfDay(ZoneOffset.UTC).toInstant(),
      price(v,"open"),price(v,"high"),price(v,"low"),price(v,"close"),price(v,"volume")));
   }
   if(bars.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Only incomplete candles returned");
   HistoryService.ImportResult result=history.importBars(new HistoryService.ImportRequest(symbol, doc.path("meta").path("name").asText(symbol),"STOCK",currency,"1d","TWELVE_DATA",bars));
   String mic = doc.path("meta").path("mic_code").asText("").toUpperCase(Locale.ROOT);
   if (mic.matches("[A-Z0-9]{4}")) {
    jdbc.update("""
     INSERT INTO asset_provider_symbols(asset_id,provider,provider_symbol,exchange_mic)
     SELECT id,'TWELVE_DATA',symbol,? FROM assets WHERE symbol=?
     ON CONFLICT DO NOTHING
     """,mic,symbol);
    jdbc.update("""
     UPDATE asset_provider_symbols SET exchange_mic=?,updated_time=CURRENT_TIMESTAMP
     WHERE provider='TWELVE_DATA' AND asset_id=(SELECT id FROM assets WHERE symbol=?)
     """,mic,symbol);
   }
   jobs.end(job,result.inserted(),null);return result;
  }catch(Exception e){jobs.end(job,0,e.getMessage()==null?"Failed":e.getMessage());throw e;}
 }
 public enum RepairStatus { INSERTED, ALREADY_PRESENT, UNAVAILABLE }
 public record RepairResult(String symbol, LocalDate date, String timeframe, String provider, RepairStatus status) {}

 public RepairResult repairDaily(String rawSymbol, LocalDate date) {
  if (rawSymbol == null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || date == null) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid symbol and date required");
  }
  String symbol = rawSymbol.toUpperCase(Locale.ROOT);
  Instant time = date.atStartOfDay(ZoneOffset.UTC).toInstant();
  var assets = jdbc.queryForList("SELECT id,currency FROM assets WHERE symbol=? AND asset_type='STOCK'", symbol);
  if (assets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown stock");
  var asset = assets.getFirst();
  long assetId = ((Number) asset.get("id")).longValue();
  if (hasDailyCandle(assetId, time)) {
   return new RepairResult(symbol, date, "1d", "TWELVE_DATA", RepairStatus.ALREADY_PRESENT);
  }

  boolean completed = Boolean.TRUE.equals(jdbc.queryForObject("""
      SELECT EXISTS (
        SELECT 1 FROM asset_market_policies p
        JOIN market_sessions s ON s.calendar_code=p.calendar_code
        WHERE p.asset_id=? AND s.session_date=? AND s.opens_at IS NOT NULL
          AND s.closes_at<=?
      )
      """, Boolean.class, assetId, java.sql.Date.valueOf(date), Timestamp.from(clock.instant())));
  if (!completed) {
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A completed, configured market session is required");
  }
  if (key.isBlank()) {
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Set TWELVE_DATA_API_KEY in environment");
  }

  long job = jobs.start(symbol, "TWELVE_DATA", "DAILY_OHLCV");
  try {
   JsonNode response = daily(symbol, date, date.plusDays(1), 2);
   JsonNode values = response.path("values");
   if (!values.isArray()) {
    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid daily candles response");
   }
   for (JsonNode value : values) {
    if (!date.toString().equals(value.path("datetime").asText())) continue;
    String currency = (String) asset.get("currency");
    if (!currency.equalsIgnoreCase(response.path("meta").path("currency").asText())) {
     throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider currency does not match the asset");
    }
    HistoryService.PriceBar bar;
    try {
     bar = new HistoryService.PriceBar(time, price(value, "open"), price(value, "high"),
             price(value, "low"), price(value, "close"), price(value, "volume"));
    } catch (NumberFormatException invalid) {
     throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid provider candle");
    }
    var result = history.importBars(new HistoryService.ImportRequest(symbol,
            response.path("meta").path("name").asText(symbol), "STOCK", currency, "1d", "TWELVE_DATA", List.of(bar)));
    jobs.end(job, result.inserted(), null);
    RepairStatus status = result.inserted() > 0 ? RepairStatus.INSERTED : RepairStatus.ALREADY_PRESENT;
    return new RepairResult(symbol, date, "1d", "TWELVE_DATA", status);
   }
   jobs.end(job, 0, null);
   return new RepairResult(symbol, date, "1d", "TWELVE_DATA", RepairStatus.UNAVAILABLE);
  } catch (RuntimeException failure) {
   jobs.end(job, 0, failure.getMessage() == null ? "Failed" : failure.getMessage());
   throw failure;
  }
 }

 private boolean hasDailyCandle(long assetId, Instant time) {
  Timestamp open = Timestamp.from(time);
  return Boolean.TRUE.equals(jdbc.queryForObject("""
      SELECT EXISTS (
        SELECT 1 FROM market_candles WHERE asset_id=? AND timeframe='1d' AND provider='TWELVE_DATA' AND open_time=?
        UNION ALL
        SELECT 1 FROM provider_price_observations WHERE asset_id=? AND timeframe='1d' AND provider='TWELVE_DATA' AND open_time=?
      )
      """, Boolean.class, assetId, open, assetId, open));
 }

 private JsonNode daily(String symbol, LocalDate start, LocalDate end, int limit) {
  String url = UriComponentsBuilder.fromUriString(base+"/time_series")
          .queryParam("symbol",symbol).queryParam("interval","1day")
          .queryParam("start_date",start).queryParam("end_date",end)
          .queryParam("outputsize",limit).queryParam("apikey",key).build().encode().toUriString();
  return http.get(ProviderOperation.TWELVE_DAILY,url,null);
 }

 private static BigDecimal price(JsonNode node,String field){return new BigDecimal(node.path(field).asText());}
}
