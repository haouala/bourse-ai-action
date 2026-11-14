package lu.bourse.engine.provider;
import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.AssetEntity;
import lu.bourse.engine.persistence.AssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
@Service
public class SecEdgarService {
 private static final List<String> CONCEPTS=List.of("Revenues","RevenueFromContractWithCustomerExcludingAssessedTax","NetIncomeLoss","Assets","Liabilities","StockholdersEquity","NetCashProvidedByUsedInOperatingActivities");
 private final ProviderHttp http;private final AssetRepository assets;private final JdbcTemplate jdbc;private final ImportJobService jobs;
 @Value("${market.sec.base-url}") private String base;
 @Value("${market.sec.user-agent:}") private String userAgent;
 public SecEdgarService(ProviderHttp http,AssetRepository assets,JdbcTemplate jdbc,ImportJobService jobs){this.http=http;this.assets=assets;this.jdbc=jdbc;this.jobs=jobs;}
 public record Fact(String concept,BigDecimal value,String unit,LocalDate periodStart,LocalDate periodEnd,LocalDate filedDate,String form,String accessionNumber){}
 public record SecResult(String symbol,String cik,int inserted){}
 public SecResult sync(String rawSymbol,int cik){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||cik<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Symbol and positive CIK required");
  if(userAgent.isBlank() || userAgent.contains("example.com"))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Set SEC_USER_AGENT with real app name/contact email");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  AssetEntity asset=assets.findBySymbol(symbol).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Import asset price history first"));
  String cikValue=String.format(Locale.ROOT,"%010d",cik);
  long job=jobs.start(symbol,"SEC_EDGAR","COMPANY_FACTS");
  try{
   JsonNode doc=http.get(base+"/api/xbrl/companyfacts/CIK"+cikValue+".json",userAgent);
   // Prevent mistakenly associating another company's data with the stock.
   if(doc.path("cik").asInt(-1)!=cik)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"CIK does not match SEC response");
   int inserted=0;JsonNode gaap=doc.path("facts").path("us-gaap");
   LocalDate since=LocalDate.now().minusYears(6);
   for(String concept:CONCEPTS){JsonNode units=gaap.path(concept).path("units");if(!units.isObject())continue;
    Iterator<Map.Entry<String,JsonNode>> iter=units.fields();
    while(iter.hasNext()){var unitEntry=iter.next();String unit=unitEntry.getKey();if(!unit.equals("USD"))continue;
     for(JsonNode x:unitEntry.getValue()){
      String form=x.path("form").asText("");if(!form.equals("10-K")&&!form.equals("10-Q"))continue;
      String end=x.path("end").asText(""),filed=x.path("filed").asText(""),accn=x.path("accn").asText("");
      if(end.isEmpty()||filed.isEmpty()||accn.isEmpty()||!x.has("val"))continue;
      LocalDate endDate=LocalDate.parse(end),filedDate=LocalDate.parse(filed);
      if(endDate.isBefore(since))continue;
      Integer count=jdbc.queryForObject("SELECT count(*) FROM financial_facts WHERE asset_id=? AND concept=? AND accession_number=? AND period_end=? AND unit=?",Integer.class,asset.getId(),concept,accn,java.sql.Date.valueOf(endDate),unit);
      if(count!=null && count>0)continue;
      LocalDate start=x.hasNonNull("start")?LocalDate.parse(x.path("start").asText()):null;
      try{jdbc.update("INSERT INTO financial_facts(asset_id,concept,value,unit,period_start,period_end,filed_date,form_type,accession_number,source) VALUES (?,?,?,?,?,?,?,?,?,?)",
       asset.getId(),concept,new BigDecimal(x.path("val").asText()),unit,start==null?null:java.sql.Date.valueOf(start),java.sql.Date.valueOf(endDate),java.sql.Date.valueOf(filedDate),form,accn,"SEC_EDGAR");inserted++;}
      catch(org.springframework.dao.DuplicateKeyException ignored){}
     }
    }
   }
   jobs.end(job,inserted,null);return new SecResult(symbol,cikValue,inserted);
  }catch(Exception e){jobs.end(job,0,e.getMessage()==null?"Failed":e.getMessage());throw e;}
 }
 public List<Fact> latest(String symbol){
  if(symbol==null||!symbol.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return jdbc.query("SELECT f.concept,f.value,f.unit,f.period_start,f.period_end,f.filed_date,f.form_type,f.accession_number FROM financial_facts f JOIN assets a ON a.id=f.asset_id WHERE a.symbol=? ORDER BY f.filed_date DESC,f.period_end DESC LIMIT 100",
    (r,i)->new Fact(r.getString(1),r.getBigDecimal(2),r.getString(3),r.getDate(4)==null?null:r.getDate(4).toLocalDate(),r.getDate(5).toLocalDate(),r.getDate(6).toLocalDate(),r.getString(7),r.getString(8)),symbol.toUpperCase(Locale.ROOT));
 }
}
