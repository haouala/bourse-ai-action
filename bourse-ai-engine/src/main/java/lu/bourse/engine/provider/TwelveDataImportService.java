package lu.bourse.engine.provider;
import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.service.HistoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
@Service
public class TwelveDataImportService {
 private final ProviderHttp http; private final HistoryService history; private final ImportJobService jobs;
 @Value("${market.twelve-data.base-url}") private String base;
 @Value("${market.twelve-data.api-key:}") private String key;
 public TwelveDataImportService(ProviderHttp http,HistoryService history,ImportJobService jobs){this.http=http;this.history=history;this.jobs=jobs;}
 public HistoryService.ImportResult sync(String rawSymbol,int years){
  if(rawSymbol==null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || years<1 || years>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or years");
  if(key.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set TWELVE_DATA_API_KEY in environment");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT); long job=jobs.start(symbol,"TWELVE_DATA","DAILY_OHLCV");
  try{
   LocalDate today=LocalDate.now(ZoneOffset.UTC);
   String url=UriComponentsBuilder.fromUriString(base+"/time_series").queryParam("symbol",symbol).queryParam("interval","1day")
     .queryParam("start_date",today.minusYears(years)).queryParam("end_date",today)
     .queryParam("outputsize",5000).queryParam("apikey",key).build().encode().toUriString();
   JsonNode doc=http.get(url,null),values=doc.path("values");
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
   jobs.end(job,result.inserted(),null);return result;
  }catch(Exception e){jobs.end(job,0,e.getMessage()==null?"Failed":e.getMessage());throw e;}
 }
 private static BigDecimal price(JsonNode node,String field){return new BigDecimal(node.path(field).asText());}
}
