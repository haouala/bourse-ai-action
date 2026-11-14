package lu.bourse.engine.provider;
import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.AssetEntity;
import lu.bourse.engine.persistence.AssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
@Service
public class GdeltNewsService {
 private final ProviderHttp http;private final AssetRepository assets;private final JdbcTemplate jdbc;private final ImportJobService jobs;
 @Value("${market.gdelt.base-url}") private String base;
 public GdeltNewsService(ProviderHttp http,AssetRepository assets,JdbcTemplate jdbc,ImportJobService jobs){this.http=http;this.assets=assets;this.jdbc=jdbc;this.jobs=jobs;}
 public record NewsRow(String title,String url,String source,Instant publishedTime,Integer sentimentScore,String sentimentMethod){}
 public record NewsSyncResult(String symbol,int inserted,int fetched){}
 public NewsSyncResult sync(String rawSymbol,int max){
  String symbol=validate(rawSymbol);if(max<1||max>75)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"max must be 1..75");
  AssetEntity asset=assets.findBySymbol(symbol).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Import price history before news"));
  long job=jobs.start(symbol,"GDELT","NEWS");
  try {
   // Search full company name, not ambiguous ticker symbol (e.g. V).
   String query="\""+asset.getName().replace("\"","")+"\"";
   String url=UriComponentsBuilder.fromUriString(base+"/api/v2/doc/doc")
    .queryParam("query",query).queryParam("mode","ArtList").queryParam("format","json")
    .queryParam("maxrecords",max).queryParam("timespan","7d").queryParam("sort","DateDesc").build().encode().toUriString();
   JsonNode articles=http.get(url,null).path("articles");int inserted=0,fetched=0;
   if(articles.isArray())for(JsonNode a:articles){
    String link=a.path("url").asText(""),title=a.path("title").asText("");
    if(!link.startsWith("https://") && !link.startsWith("http://"))continue;
    if(title.isBlank())continue; fetched++;
    if(jdbc.queryForObject("SELECT count(*) FROM market_news WHERE asset_id=? AND url=?",Integer.class,asset.getId(),link)>0)continue;
    Instant time=parseTime(a.path("seendate").asText(""));
    String source=a.path("domain").asText("GDELT");
    try {jdbc.update("INSERT INTO market_news(asset_id,source,title,url,published_time,sentiment_score,sentiment_method,source_country,language,region_tag) VALUES (?,?,?,?,?,?,?,?,?,?)",
       asset.getId(),cut(source,120),cut(title,1500),cut(link,2000),null,null,null,cut(a.path("sourcecountry").asText(""),10),cut(a.path("language").asText(""),20),null);inserted++;}
    catch(org.springframework.dao.DuplicateKeyException ignored){}
   }
   jobs.end(job,inserted,null);return new NewsSyncResult(symbol,inserted,fetched);
  }catch(Exception e){jobs.end(job,0,e.getMessage()==null?"Failed":e.getMessage());throw e;}
 }
 public List<NewsRow> list(String symbol){
  String s=validate(symbol);return jdbc.query("SELECT title,url,source,published_time,sentiment_score,sentiment_method FROM market_news n JOIN assets a ON a.id=n.asset_id WHERE a.symbol=? ORDER BY n.published_time DESC LIMIT 100",
   (rs,i)->new NewsRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant(),(Integer)rs.getObject(5),rs.getString(6)),s);
 }
 private static String cut(String x,int max){return x.length()>max?x.substring(0,max):x;}
 private static Instant parseTime(String raw){try{return LocalDateTime.parse(raw,DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);}catch(Exception e){return null;}}
 private static String validate(String x){if(x==null||!x.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");return x.toUpperCase(Locale.ROOT);}
}
