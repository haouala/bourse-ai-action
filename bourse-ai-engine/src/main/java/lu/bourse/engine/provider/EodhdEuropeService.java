package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.*;
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

/** Free EODHD: last 1 year ONLY and 20 calls/day, not a 5-year European feed. */
@Service
public class EodhdEuropeService {
 private final ProviderHttp http; private final HistoryService history;
 private final AssetRepository assets;private final JdbcTemplate jdbc;private final ImportJobService jobs;
 @Value("${market.eodhd.base-url}") private String base;
 @Value("${market.eodhd.api-key:}") private String key;
 public record SyncResult(String symbol,String providerSymbol,int observations,int canonicalInserted,int canonicalSkipped,String provider,String coverageNotice){}
 public record ProviderPrice(String provider,String providerSymbol,String timeframe,Instant time,BigDecimal close,String currency,String adjustment){}
 public EodhdEuropeService(ProviderHttp http,HistoryService history,AssetRepository assets,JdbcTemplate jdbc,ImportJobService jobs){
  this.http=http;this.history=history;this.assets=assets;this.jdbc=jdbc;this.jobs=jobs;
 }
 public SyncResult sync(String rawSymbol,String rawProviderSymbol,String rawCurrency,String rawName){
  if(rawSymbol==null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || rawProviderSymbol==null || !rawProviderSymbol.matches("[A-Za-z0-9._-]{1,80}") || rawCurrency==null || !rawCurrency.matches("[A-Z]{3}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol/providerSymbol/currency");
  if(key.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set EODHD_API_KEY");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT),providerSymbol=rawProviderSymbol.toUpperCase(Locale.ROOT);
  String name=(rawName==null||rawName.isBlank())?symbol:rawName;
  if(name.length()>255)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Name too long");
  long job=jobs.start(symbol,"EODHD","EU_DAILY_OHLCV");
  try {
   LocalDate to=LocalDate.now(ZoneOffset.UTC),from=to.minusYears(1);
   String url=UriComponentsBuilder.fromUriString(base+"/api/eod/"+providerSymbol)
    .queryParam("from",from).queryParam("to",to.minusDays(1))
    .queryParam("fmt","json").queryParam("api_token",key).build().encode().toUriString();
   JsonNode response=http.get(ProviderOperation.EODHD_DAILY,url,null);
   if(!response.isArray() || response.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"EODHD returned no candles: verify free-plan symbol and access");
   List<HistoryService.PriceBar> bars=new ArrayList<>();
   for(JsonNode b:response){
    LocalDate date=LocalDate.parse(b.path("date").asText());
    if(!date.isBefore(to))continue;
    bars.add(new HistoryService.PriceBar(date.atStartOfDay(ZoneOffset.UTC).toInstant(),
      value(b,"open"),value(b,"high"),value(b,"low"),value(b,"close"),value(b,"volume")));
   }
   if(bars.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"No complete daily candles");
   // Only the first provider is selected for the canonical series; alternatives remain available for comparison.
   HistoryService.ImportResult result=history.importBars(new HistoryService.ImportRequest(symbol,name,"STOCK",rawCurrency,"1d","EODHD",bars));
   AssetEntity asset=assets.findBySymbol(symbol).orElseThrow();
   int stored=0;
   for(HistoryService.PriceBar b:bars){
    Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM provider_price_observations WHERE asset_id=? AND provider='EODHD' AND timeframe='1d' AND open_time=?",Integer.class,asset.getId(),Timestamp.from(b.openTime()));
    if(exists!=null && exists>0)continue;
    jdbc.update("INSERT INTO provider_price_observations(asset_id,provider,provider_symbol,timeframe,open_time,open_price,high_price,low_price,close_price,volume,currency,price_adjustment) VALUES (?, 'EODHD', ?, '1d', ?, ?, ?, ?, ?, ?, ?, 'UNSPECIFIED')",
     asset.getId(),providerSymbol,Timestamp.from(b.openTime()),b.open(),b.high(),b.low(),b.close(),b.volume(),rawCurrency);stored++;
   }
   Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM asset_provider_symbols WHERE asset_id=? AND provider='EODHD'",Integer.class,asset.getId());
   if(n!=null && n==0)jdbc.update("INSERT INTO asset_provider_symbols(asset_id,provider,provider_symbol) VALUES (?, 'EODHD', ?)",asset.getId(),providerSymbol);
   else jdbc.update("UPDATE asset_provider_symbols SET provider_symbol=?,updated_time=CURRENT_TIMESTAMP WHERE asset_id=? AND provider='EODHD'",providerSymbol,asset.getId());
   jobs.end(job,stored,null);
   return new SyncResult(symbol,providerSymbol,stored,result.inserted(),result.skipped(),"EODHD","FREE plan: 1 year max; provenance kept separately");
  }catch(Exception e){jobs.end(job,0,e.getMessage()==null?"Failed":e.getMessage());throw e;}
 }
 public List<ProviderPrice> observations(String rawSymbol){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return jdbc.query("SELECT p.provider,p.provider_symbol,p.timeframe,p.open_time,p.close_price,p.currency,p.price_adjustment FROM provider_price_observations p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=? ORDER BY p.open_time DESC LIMIT 100",
   (r,i)->new ProviderPrice(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(),r.getBigDecimal(5),r.getString(6),r.getString(7)),rawSymbol.toUpperCase(Locale.ROOT));
 }
 private static BigDecimal value(JsonNode node,String name){
  if(node.path(name).isNull()||node.path(name).isMissingNode())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Missing "+name);
  try{return new BigDecimal(node.path(name).asText());}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid "+name);}
 }
}
