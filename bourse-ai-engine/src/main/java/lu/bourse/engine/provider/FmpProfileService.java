package lu.bourse.engine.provider;
import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigDecimal;
import java.util.Locale;
@Service
public class FmpProfileService {
 private final ProviderHttp http;private final AssetRepository assets;private final JdbcTemplate jdbc;private final ImportJobService jobs;
 @Value("${market.fmp.api-key:}") private String key;
 @Value("${market.fmp.base-url}") private String base;
 public record Profile(String symbol,String name,String exchange,String sector,String industry,String website,BigDecimal marketCap,String source){}
 public FmpProfileService(ProviderHttp http,AssetRepository assets,JdbcTemplate jdbc,ImportJobService jobs){this.http=http;this.assets=assets;this.jdbc=jdbc;this.jobs=jobs;}
 public Profile sync(String rawSymbol){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  if(key.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set FMP_API_KEY first");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  AssetEntity asset=assets.findBySymbol(symbol).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Import asset first"));
  long job=jobs.start(symbol,"FMP","PROFILE");
  try{
   String url=UriComponentsBuilder.fromUriString(base+"/stable/profile").queryParam("symbol",symbol).queryParam("apikey",key).build().encode().toUriString();
   JsonNode list=http.get(ProviderOperation.FMP_PROFILE,url,null);if(!list.isArray()||list.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"FMP profile unavailable for symbol/plan");
   JsonNode item=list.get(0);
   if(!symbol.equalsIgnoreCase(item.path("symbol").asText()))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Provider returned another symbol");
   Profile profile=new Profile(symbol,cut(item.path("companyName").asText(""),255),cut(item.path("exchangeFullName").asText(""),120),cut(item.path("sector").asText(""),120),cut(item.path("industry").asText(""),200),cut(item.path("website").asText(""),500),item.path("marketCap").isNumber()?item.path("marketCap").decimalValue():null,"FMP");
   jdbc.update("DELETE FROM company_profiles WHERE asset_id=?",asset.getId());
   jdbc.update("INSERT INTO company_profiles(asset_id,company_name,exchange_name,sector,industry,website,market_cap,source) VALUES(?,?,?,?,?,?,?,?)",asset.getId(),profile.name(),profile.exchange(),profile.sector(),profile.industry(),profile.website(),profile.marketCap(),"FMP");
   jobs.end(job,1,null);return profile;
  }catch(Exception ex){jobs.end(job,0,ex.getMessage()==null?"Failed":ex.getMessage());throw ex;}
 }
 public Profile get(String rawSymbol){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  return jdbc.query("SELECT a.symbol,p.company_name,p.exchange_name,p.sector,p.industry,p.website,p.market_cap,p.source FROM company_profiles p JOIN assets a ON a.id=p.asset_id WHERE a.symbol=?",(r,i)->new Profile(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBigDecimal(7),r.getString(8)),symbol).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Profile not imported"));
 }
 private static String cut(String x,int n){return x.length()>n?x.substring(0,n):x;}
}
