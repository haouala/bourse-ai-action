package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.*;
import java.util.*;

/** Official SEC Form 4 ingestion. No assessment of wrongdoing or profit probability. */
@Service
public class InsiderFilingService {
 private final ProviderHttp provider;
 private final AssetRepository assets;
 private final JdbcTemplate jdbc;
 private final HttpClient xmlClient=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
  .followRedirects(HttpClient.Redirect.NEVER).build();
 @Value("${market.sec.base-url}") private String submissionsBase;
 @Value("${market.sec.archives-base-url:https://www.sec.gov}") private String archivesBase;
 @Value("${market.sec.user-agent:}") private String agent;

 public InsiderFilingService(ProviderHttp provider,AssetRepository assets,JdbcTemplate jdbc){
  this.provider=provider;this.assets=assets;this.jdbc=jdbc;
 }
 public record SyncResult(String symbol,int cik,int filingsExamined,int transactionsInserted,int skippedFilings){}
 public record InsiderRow(String owner,LocalDate transactionDate,LocalDate filedDate,String code,String direction,
  BigDecimal shares,BigDecimal price,BigDecimal sharesAfter,String accession,String url,
  boolean amended,boolean derivative,boolean footnotes){}
 public record Alert(String owner,String severity,int sales,int purchases,BigDecimal soldShares,BigDecimal boughtShares,
  String explanation){}
 private record Owner(String name,Long cik,boolean officer,boolean director,boolean tenPercent){}

 public SyncResult sync(String rawSymbol,int cik,int maxFilings){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||cik<=0||maxFilings<1||maxFilings>30)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol, CIK or maxFilings");
  if(agent.isBlank()||agent.contains("example.com"))
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure SEC_USER_AGENT with real contact information");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  AssetEntity asset=assets.findBySymbol(symbol).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Import asset first"));
  JsonNode filings=provider.get(ProviderOperation.SEC_FACTS,
   submissionsBase+"/submissions/CIK"+String.format(Locale.ROOT,"%010d",cik)+".json",agent);
  if(filings.path("cik").asLong(-1)!=cik)
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SEC issuer CIK mismatch");
  JsonNode recent=filings.path("filings").path("recent");
  JsonNode forms=recent.path("form"),accessions=recent.path("accessionNumber"),documents=recent.path("primaryDocument"),dates=recent.path("filingDate");
  if(!forms.isArray()||!accessions.isArray()||!documents.isArray()||!dates.isArray())
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SEC submissions listing incomplete");
  int examined=0,inserted=0,skipped=0;
  for(int i=0;i<forms.size()&&examined<maxFilings;i++){
   String form=forms.path(i).asText("");
   if(!form.equals("4")&&!form.equals("4/A"))continue;
   examined++;
   String accession=accessions.path(i).asText(""),file=documents.path(i).asText("");
   // Never allow data returned by a remote service to redirect our requests off SEC.
   if(!accession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}")||!file.matches("[A-Za-z0-9._-]{1,150}")||!file.toLowerCase(Locale.ROOT).endsWith(".xml")){skipped++;continue;}
   LocalDate filed;
   try{filed=LocalDate.parse(dates.path(i).asText());}catch(Exception ex){skipped++;continue;}
   String url=archivesBase+"/Archives/edgar/data/"+cik+"/"+accession.replace("-","")+"/"+file;
   try {
    Document doc=fetchXml(url);
    Element root=doc.getDocumentElement();
    if(root==null||!root.getTagName().equals("ownershipDocument")){skipped++;continue;}
    if(!text(root,"issuerCik").replaceFirst("^0+(?!$)","").equals(String.valueOf(cik))){skipped++;continue;}
    List<Owner> owners=owners(root);
    if(owners.isEmpty()){skipped++;continue;}
    NodeList transactions=root.getElementsByTagName("nonDerivativeTransaction");
    for(int j=0;j<transactions.getLength();j++){
     Element t=(Element)transactions.item(j);
     String code=text(t,"transactionCode"),direction=text(t,"transactionAcquiredDisposedCode");
     String dateText=text(t,"transactionDate");
     if(!code.matches("[A-Z]{1,3}")||!direction.matches("[AD]"))continue;
     BigDecimal shares=number(t,"transactionShares"),price=number(t,"transactionPricePerShare"),after=number(t,"sharesOwnedFollowingTransaction");
     if(shares==null||shares.signum()<0)continue;
     LocalDate tradeDate;try{tradeDate=LocalDate.parse(dateText);}catch(Exception ex){continue;}
     for(Owner owner:owners){
      int ownerIndex=owners.indexOf(owner);
      int uniqueIndex=j*100+ownerIndex;
      if(jdbc.queryForObject("SELECT COUNT(*) FROM insider_transactions WHERE asset_id=? AND accession_number=? AND owner_name=? AND transaction_index=?",Integer.class,
        asset.getId(),accession,owner.name(),uniqueIndex)>0)continue;
      try{
       inserted+=jdbc.update("INSERT INTO insider_transactions(asset_id,issuer_cik,accession_number,transaction_index,owner_name,owner_cik,officer,director,ten_percent_owner,security_title,transaction_date,filed_date,transaction_code,acquired_disposed,shares,price_per_share,shares_owned_after,direct_indirect,is_derivative,is_amendment,footnote_present,source_url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        asset.getId(),cik,accession,uniqueIndex,owner.name(),owner.cik(),owner.officer(),owner.director(),owner.tenPercent(),
        truncate(text(t,"securityTitle"),255),Date.valueOf(tradeDate),Date.valueOf(filed),code,direction,shares,price,after,
        truncate(text(t,"directOrIndirectOwnership"),8),false,form.equals("4/A"),t.getElementsByTagName("footnoteId").getLength()>0,url);
      }catch(org.springframework.dao.DuplicateKeyException ignored){}
     }
    }
   }catch(ResponseStatusException e){skipped++;} // one inaccessible filing should not poison the batch
  }
  return new SyncResult(symbol,cik,examined,inserted,skipped);
 }
 private Document fetchXml(String url){
  try{
   URI uri=URI.create(url);
   // Only trusted SEC host (or an explicitly configured local test URL).
   URI configured=URI.create(archivesBase);
   if(!Objects.equals(uri.getHost(),configured.getHost())||!Objects.equals(uri.getScheme(),configured.getScheme())||uri.getPort()!=configured.getPort())
    throw new IllegalArgumentException("Cross-host URL");
   HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent",agent)
    .header("Accept","application/xml,text/xml").GET().build();
   HttpResponse<byte[]> response=xmlClient.send(request,HttpResponse.BodyHandlers.ofByteArray());
   if(response.statusCode()!=200||response.body().length>2_000_000)throw new IllegalStateException("SEC XML unavailable or oversized");
   DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
   factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
   factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
   factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
   factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
   factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
   return factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()));
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SEC Form 4 XML unavailable or invalid");}
 }
 private static List<Owner> owners(Element root){
  List<Owner> result=new ArrayList<>();
  NodeList list=root.getElementsByTagName("reportingOwner");
  for(int i=0;i<list.getLength();i++){
   Element el=(Element)list.item(i);String name=text(el,"rptOwnerName");
   if(name.isBlank())continue;
   Long cik=null;try{cik=Long.valueOf(text(el,"rptOwnerCik"));}catch(NumberFormatException ignored){}
   result.add(new Owner(truncate(name,255),cik,"1".equals(text(el,"isOfficer")),"1".equals(text(el,"isDirector")),"1".equals(text(el,"isTenPercentOwner"))));
  }
  return result;
 }
 private static String text(Element root,String tag){
  NodeList n=root.getElementsByTagName(tag);
  if(n.getLength()==0)return "";
  Element element=(Element)n.item(0);
  NodeList values=element.getElementsByTagName("value");
  return (values.getLength()>0?values.item(0):element).getTextContent().trim();
 }
 private static BigDecimal number(Element e,String tag){
  try{String s=text(e,tag);return s.isBlank()?null:new BigDecimal(s);}catch(NumberFormatException ex){return null;}
 }
 private static String truncate(String s,int length){return s.length()>length?s.substring(0,length):s;}

 public List<InsiderRow> history(String rawSymbol,int limit){
  if(rawSymbol==null||!rawSymbol.matches("[A-Za-z0-9._-]{1,40}")||limit<1||limit>200)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or limit");
  return jdbc.query("SELECT t.owner_name,t.transaction_date,t.filed_date,t.transaction_code,t.acquired_disposed,t.shares,t.price_per_share,t.shares_owned_after,t.accession_number,t.source_url,t.is_amendment,t.is_derivative,t.footnote_present FROM insider_transactions t JOIN assets a ON a.id=t.asset_id WHERE a.symbol=? ORDER BY t.transaction_date DESC,t.id DESC LIMIT ?",
   (r,i)->new InsiderRow(r.getString(1),r.getDate(2).toLocalDate(),r.getDate(3).toLocalDate(),r.getString(4),r.getString(5),
    r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8),r.getString(9),r.getString(10),r.getBoolean(11),r.getBoolean(12),r.getBoolean(13)),
   rawSymbol.toUpperCase(Locale.ROOT),limit);
 }
 public List<Alert> alerts(String rawSymbol,int days){
  if(days<1||days>365)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"days must be 1..365");
  String symbol=rawSymbol==null?"":rawSymbol.toUpperCase(Locale.ROOT);
  if(!symbol.matches("[A-Z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  return jdbc.query("SELECT t.owner_name, SUM(CASE WHEN t.transaction_code='S' AND t.acquired_disposed='D' THEN 1 ELSE 0 END), SUM(CASE WHEN t.transaction_code='P' AND t.acquired_disposed='A' THEN 1 ELSE 0 END), COALESCE(SUM(CASE WHEN t.transaction_code='S' AND t.acquired_disposed='D' THEN t.shares ELSE 0 END),0), COALESCE(SUM(CASE WHEN t.transaction_code='P' AND t.acquired_disposed='A' THEN t.shares ELSE 0 END),0) FROM insider_transactions t JOIN assets a ON a.id=t.asset_id WHERE a.symbol=? AND t.transaction_date>=? GROUP BY t.owner_name ORDER BY t.owner_name",
   (r,i)->{
    int sales=r.getInt(2),purchases=r.getInt(3);
    BigDecimal sold=r.getBigDecimal(4),bought=r.getBigDecimal(5);
    String severity=sales>=3?"REVIEW_MULTIPLE_SALES":sales>0?"INFORMATIONAL_SALE":purchases>0?"INFORMATIONAL_BUY":"NONE";
    return new Alert(r.getString(1),severity,sales,purchases,sold,bought,"Heuristic only; Form 4 codes S/P, option exercises and 10b5-1 plans need separate review; no inference of wrongdoing");
   },symbol,Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(days)));
 }
}
