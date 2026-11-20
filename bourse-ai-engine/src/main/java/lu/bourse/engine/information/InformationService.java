package lu.bourse.engine.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.seasonality.SeasonalityService;
import lu.bourse.engine.social.SocialSignalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.springframework.http.HttpStatus.*;

@Service
public class InformationService {
    public record Source(String code,String name,String feedUrl,String evidenceUrl,String region,String language,String contentKind,String publisherGroup){}
    private final JdbcTemplate db;private final Clock clock;private final ObjectMapper json;private final SocialSignalService social;
    private final TransactionTemplate tx;private final HttpClient http;private final Resource catalog;private final boolean scheduler;
    private final String testBase;private final int minimumInterval;private final FeedParser parser=new FeedParser();
    public InformationService(JdbcTemplate db,Clock clock,ObjectMapper json,SocialSignalService social,PlatformTransactionManager manager,
        @Value("${information.catalog:classpath:information-sources.json}") Resource catalog,
        @Value("${information.scheduler.enabled:false}") boolean scheduler,
        @Value("${information.minimum-interval-seconds:3600}") int minimumInterval,
        @Value("${information.test-base-url:}") String testBase){
        this.db=db;this.clock=clock;this.json=json;this.social=social;this.catalog=catalog;this.scheduler=scheduler;this.minimumInterval=Math.max(60,minimumInterval);this.testBase=testBase;
        if(!testBase.isBlank()&&!testBase.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IllegalArgumentException("Test base must be loopback HTTP");
        tx=new TransactionTemplate(manager);http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @PostConstruct void seed(){tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM watchlist_guard WHERE id=1 FOR UPDATE",Integer.class);seedUnlocked();});}
    private void seedUnlocked(){
        try(var in=catalog.getInputStream()){
            Source[] sources=json.readValue(in,Source[].class);
            for(Source s:sources){
                if(s.code()==null||!s.code().matches("[A-Z0-9_]{2,60}")||s.name()==null||s.name().length()>160||s.region()==null||s.region().length()>30||s.language()==null||s.language().length()>12||!safeConfiguredUrl(s.feedUrl())||!safeConfiguredUrl(s.evidenceUrl()))throw new IllegalArgumentException("Invalid source catalog entry");
                if(db.queryForObject("SELECT COUNT(*) FROM information_sources WHERE code=?",Integer.class,s.code())==0)db.update("INSERT INTO information_sources(code,name,feed_url,evidence_url,region,language_code,content_kind,publisher_group) VALUES (?,?,?,?,?,?,?,?)",s.code(),s.name(),s.feedUrl(),s.evidenceUrl(),s.region(),s.language(),s.contentKind()==null?"ARTICLE":s.contentKind(),s.publisherGroup()==null?(s.code().startsWith("FED_")?"FED":s.code().startsWith("BOE_")?"BOE":s.code()):s.publisherGroup());
                // Never silently replace a configured feed or publisher identity; explicit catalog migrations are reviewable.
            }
        }catch(Exception e){throw new IllegalStateException("Cannot load information source catalog",e);}
    }
    public List<Map<String,Object>> sources(){return db.queryForList("SELECT code,name,feed_url,evidence_url,region,language_code,content_kind,publisher_group,enabled,last_success_at,last_error,next_attempt_at FROM information_sources ORDER BY code");}
    public void enable(String code,boolean value){if(db.update("UPDATE information_sources SET enabled=?,updated_time=CURRENT_TIMESTAMP WHERE code=?",value,code)==0)throw new ResponseStatusException(NOT_FOUND,"Unknown source");}
    public Map<String,Object> sync(String code){
        Instant now=clock.instant();String token=UUID.randomUUID().toString();
        var claim=tx.execute(status->{
            var list=db.queryForList("SELECT * FROM information_sources WHERE code=? FOR UPDATE",code);if(list.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown source");var s=list.getFirst();
            if(!(Boolean)s.get("enabled"))throw new ResponseStatusException(CONFLICT,"Enable this source explicitly first");
            if(s.get("lease_until")!=null&&SqlIds.instant(s.get("lease_until")).isAfter(now))throw new ResponseStatusException(CONFLICT,"Source collection already running");
            if(s.get("next_attempt_at")!=null&&SqlIds.instant(s.get("next_attempt_at")).isAfter(now))throw new ResponseStatusException(TOO_MANY_REQUESTS,"Source polling interval or backoff active");
            if(s.get("lease_token")!=null)db.update("UPDATE information_sync_runs SET status='ABANDONED',finished_at=?,error_code='LEASE_EXPIRED' WHERE source_code=? AND status='RUNNING'",ts(now),code);
            db.update("UPDATE information_sources SET lease_token=?,lease_until=?,next_attempt_at=? WHERE code=?",token,ts(now.plusSeconds(60)),ts(now.plusSeconds(minimumInterval)),code);
            long job=SqlIds.insert(db,"INSERT INTO information_sync_runs(source_code,started_at,status) VALUES (?,?,'RUNNING')",code,ts(now));
            return new Claim(s,job);
        });
        try{
            String address=testBase.isBlank()?(String)claim.source().get("feed_url"):testBase+"/"+code;
            var request=HttpRequest.newBuilder(URI.create(address)).GET().timeout(Duration.ofSeconds(20)).header("User-Agent","BourseAI/0.2 RSS reader").header("Accept","application/rss+xml, application/atom+xml, application/xml, text/xml");
            if(claim.source().get("etag")!=null)request.header("If-None-Match",(String)claim.source().get("etag"));
            if(claim.source().get("last_modified")!=null)request.header("If-Modified-Since",(String)claim.source().get("last_modified"));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()){
                if(response.statusCode()==304){return complete(claim,token,List.of(),"NOT_MODIFIED",response);}
                if(response.statusCode()!=200)throw new CollectionFailure("HTTP_"+response.statusCode(),response.statusCode()==429?3600:minimumInterval);
                byte[] bytes=stream.readNBytes(FeedParser.MAX_BYTES+1);var items=parser.parse(bytes);
                return complete(claim,token,items,"SUCCEEDED",response);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail(claim,token,"INTERRUPTED",minimumInterval);throw new ResponseStatusException(BAD_GATEWAY,"Information collection interrupted");}
        catch(Exception e){String codeError=e instanceof CollectionFailure f?f.code:e instanceof IllegalArgumentException?"INVALID_FEED":"CONNECTION_FAILED";int backoff=e instanceof CollectionFailure f?f.backoff:minimumInterval;fail(claim,token,codeError,backoff);throw new ResponseStatusException(BAD_GATEWAY,"Information collection failed: "+codeError);}
    }
    private Map<String,Object> complete(Claim claim,String token,List<FeedParser.Item> items,String status,HttpResponse<?> response){return tx.execute(transaction->{
        String code=(String)claim.source().get("code");var source=locked(code,token);
        Long publisher=source.get("publisher_actor_id")==null?null:((Number)source.get("publisher_actor_id")).longValue();
        if(publisher==null&&!items.isEmpty()){
            publisher=social.actor(new SocialSignalService.Actor((String)source.get("name"),"Institutional publisher; individual speaker not inferred","OFFICIAL_WEB",code,true,(String)source.get("evidence_url")));
            db.update("UPDATE information_sources SET publisher_actor_id=? WHERE code=?",publisher,code);
        }
        int inserted=0;Instant collected=clock.instant();
        for(var item:items){
            String key=SeasonalityService.hash(item.key()),hash=SeasonalityService.hash(item.title()+"\n"+item.summary()+"\n"+item.publishedAt()+"\n"+item.url()+"\n"+item.author()+(item.audioUrl()==null&&item.transcriptUrl()==null?"":"\n"+item.audioUrl()+"\n"+item.transcriptUrl()));
            if(db.queryForObject("SELECT COUNT(*) FROM information_items WHERE source_code=? AND external_key=? AND content_hash=?",Integer.class,code,key,hash)>0)continue;
            String review=item.publishedAt()==null?"DATE_MISSING":item.publishedAt().isAfter(collected)?"FUTURE_DATE":"PENDING_REVIEW";Long publication=null;
            if(review.equals("PENDING_REVIEW"))publication=social.publication(new SocialSignalService.Publication(publisher,key+"-"+hash.substring(0,12),item.title()+"\n"+item.summary(),item.url(),item.publishedAt()));
            long id=SqlIds.insert(db,"INSERT INTO information_items(source_code,external_key,content_hash,title,summary,original_url,author_text,published_at,collected_at,publication_id,review_status) VALUES (?,?,?,?,?,?,?,?,?,?,?)",code,key,hash,item.title(),item.summary(),item.url(),item.author(),ts(item.publishedAt()),ts(collected),publication,review);inserted++;
            db.update("UPDATE information_items SET audio_url=?,transcript_url=? WHERE id=?",item.audioUrl(),item.transcriptUrl(),id);
            for(var actor:db.queryForList("SELECT id,display_name FROM social_actors WHERE role_label NOT LIKE 'Institutional publisher%'")){
                String name=(String)actor.get("display_name");
                if(Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(name)+"(?![\\p{L}\\p{N}])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(item.title()+" "+item.summary()).find())db.update("INSERT INTO information_mentions(item_id,actor_id,matched_name) VALUES (?,?,?)",id,actor.get("id"),name);
            }
        }
        db.update("UPDATE information_sync_runs SET status=?,finished_at=?,inserted_count=? WHERE id=?",status,ts(collected),inserted,claim.job());
        db.update("UPDATE information_sources SET etag=?,last_modified=?,last_success_at=?,last_error=NULL,lease_until=NULL,lease_token=NULL,updated_time=CURRENT_TIMESTAMP WHERE code=?",header(response,"ETag"),header(response,"Last-Modified"),ts(collected),code);
        return Map.of("source",code,"status",status,"inserted",inserted,"classification","PENDING_REVIEW");
    });}
    private void fail(Claim claim,String token,String error,int backoff){tx.executeWithoutResult(status->{String code=(String)claim.source().get("code");int changed=db.update("UPDATE information_sources SET lease_until=NULL,lease_token=NULL,last_error=?,next_attempt_at=?,updated_time=CURRENT_TIMESTAMP WHERE code=? AND lease_token=?",error,ts(clock.instant().plusSeconds(backoff)),code,token);db.update("UPDATE information_sync_runs SET status=?,finished_at=?,error_code=? WHERE id=?",changed==0?"LEASE_LOST":"FAILED",ts(clock.instant()),error,claim.job());});}
    private Map<String,Object> locked(String code,String token){var s=db.queryForMap("SELECT * FROM information_sources WHERE code=? FOR UPDATE",code);if(!token.equals(s.get("lease_token")))throw new IllegalStateException("LEASE_LOST");return s;}
    @Scheduled(fixedDelayString="${information.scheduler.delay-ms:60000}") public void scheduled(){
        if(!scheduler)return;Instant now=clock.instant();var due=db.queryForList("SELECT code FROM information_sources WHERE enabled=TRUE AND (next_attempt_at IS NULL OR next_attempt_at<=?) AND (lease_until IS NULL OR lease_until<=?) ORDER BY code LIMIT 1",String.class,ts(now),ts(now));
        if(!due.isEmpty())try{sync(due.getFirst());}catch(ResponseStatusException ignored){} // Error persisted, no response/body/token logged.
    }
    public List<Map<String,Object>> items(String source){return source==null?db.queryForList("SELECT * FROM information_items ORDER BY id DESC LIMIT 200"):db.queryForList("SELECT * FROM information_items WHERE source_code=? ORDER BY id DESC LIMIT 200",source);}
    public List<Map<String,Object>> mentions(long item){return db.queryForList("SELECT * FROM information_mentions WHERE item_id=? ORDER BY actor_id",item);}
    public List<Map<String,Object>> runs(){return db.queryForList("SELECT * FROM information_sync_runs ORDER BY id DESC LIMIT 100");}
    public record Attribution(long actorId,String exactQuote,String evidenceUrl){}
    public Map<String,Long> attribute(long itemId,Attribution r){return tx.execute(status->{
        var items=db.queryForList("SELECT * FROM information_items WHERE id=? FOR UPDATE",itemId);
        if(items.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown item");var item=items.getFirst();
        if(r==null||r.exactQuote()==null||r.exactQuote().length()<10||r.exactQuote().length()>2000||!safeConfiguredUrl(r.evidenceUrl()))throw new ResponseStatusException(BAD_REQUEST,"Exact quote and evidence required");
        if(!((String)item.get("title")+"\n"+item.get("summary")).contains(r.exactQuote()))throw new ResponseStatusException(UNPROCESSABLE_ENTITY,"Quote must occur verbatim in collected text");
        if(!"PENDING_REVIEW".equals(item.get("review_status"))||db.queryForObject("SELECT COUNT(*) FROM social_actors WHERE id=?",Integer.class,r.actorId())==0)throw new ResponseStatusException(BAD_REQUEST,"Dated publication and registered actor required");
        String hash=SeasonalityService.hash(r.exactQuote());var old=db.queryForList("SELECT publication_id FROM information_attributions WHERE item_id=? AND actor_id=? AND quote_hash=?",Long.class,itemId,r.actorId(),hash);
        if(!old.isEmpty())return Map.of("publicationId",old.getFirst());
        long publication=social.publication(new SocialSignalService.Publication(r.actorId(),"quote-"+itemId+"-"+hash.substring(0,20),r.exactQuote(),(String)item.get("original_url"),SqlIds.instant(item.get("published_at"))));
        db.update("UPDATE social_publications SET attribution_kind='HUMAN_VERIFIED_QUOTE' WHERE id=?",publication);
        SqlIds.insert(db,"INSERT INTO information_attributions(item_id,actor_id,quote_hash,exact_quote,evidence_url,publication_id,verified_at) VALUES (?,?,?,?,?,?,?)",itemId,r.actorId(),hash,r.exactQuote(),r.evidenceUrl(),publication,ts(clock.instant()));
        db.update("UPDATE information_mentions SET attribution_status='HUMAN_VERIFIED_QUOTE',evidence=? WHERE item_id=? AND actor_id=?",r.evidenceUrl(),itemId,r.actorId());
        return Map.of("publicationId",publication);
    });}
    private record Claim(Map<String,Object> source,long job){}
    private static class CollectionFailure extends RuntimeException {final String code;final int backoff;CollectionFailure(String code,int backoff){this.code=code;this.backoff=backoff;}}
    private static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}
    private static String header(HttpResponse<?> r,String key){return r.headers().firstValue(key).filter(x->x.length()<=1000).orElse(null);}
    private static boolean safeConfiguredUrl(String value){try{URI u=URI.create(value);String host=u.getHost();return value.length()<=1500&&"https".equals(u.getScheme())&&u.getUserInfo()==null&&host!=null&&host.contains(".")&&!host.matches("[0-9.]+")&&!host.equals("localhost")&&!host.endsWith(".local");}catch(Exception e){return false;}}
}
