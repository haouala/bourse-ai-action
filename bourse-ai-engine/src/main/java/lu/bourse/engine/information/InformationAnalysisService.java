package lu.bourse.engine.information;

import com.fasterxml.jackson.databind.*;
import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.seasonality.SeasonalityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** AI proposals are auditable evidence, never automatic confirmation of a fact. */
@Service
public class InformationAnalysisService {
    public record Completion(String contentHash,int analyzedCharacters,JsonNode analysis){}
    public record Review(boolean approved,String reason,Map<String,String> eventGroups){}
    private final JdbcTemplate db;private final Clock clock;private final ObjectMapper json;private final InformationWorkService work;
    public InformationAnalysisService(JdbcTemplate db,Clock clock,ObjectMapper json,InformationWorkService work){this.db=db;this.clock=clock;this.json=json;this.work=work;}
    @Transactional public long complete(long jobId,String lease,Completion result){
        // A lost HTTP response can be retried while the same completed lease is retained.
        var saved=db.queryForList("SELECT a.id,a.result_json,j.lease_token,c.content_hash,a.analyzed_characters FROM information_ai_analyses a JOIN information_ai_jobs j ON j.id=a.job_id JOIN information_contents c ON c.id=a.content_id WHERE a.job_id=?",jobId);
        if(!saved.isEmpty()){
            var old=saved.getFirst();if(result!=null&&Objects.equals(lease,old.get("lease_token"))&&Objects.equals(result.contentHash(),old.get("content_hash"))&&((Number)old.get("analyzed_characters")).intValue()==result.analyzedCharacters()&&read((String)old.get("result_json")).equals(result.analysis()))return ((Number)old.get("id")).longValue();
            throw new ResponseStatusException(CONFLICT,"Completed result is immutable");
        }
        var job=work.locked(jobId,lease);if(job.get("content_id")==null)bad("Persist source content before analysis");
        var content=db.queryForMap("SELECT * FROM information_contents WHERE id=?",job.get("content_id"));String text=(String)content.get("body_text");
        if(result==null||!Objects.equals(result.contentHash(),content.get("content_hash"))||result.analyzedCharacters()!=text.length())bad("Result must cover exactly the persisted content");
        JsonNode a=result.analysis();validate(a,text);
        long id=SqlIds.insert(db,"INSERT INTO information_ai_analyses(job_id,content_id,result_json,analyzed_characters,created_time) VALUES (?,?,?,?,?)",jobId,job.get("content_id"),a.toString(),result.analyzedCharacters(),ts(clock.instant()));
        Set<String> seen=new HashSet<>();
        for(JsonNode event:a.get("events"))for(JsonNode impact:event.get("impacts")){
            String symbol=impact.get("symbol").asText();var assets=db.queryForList("SELECT id FROM assets WHERE symbol=?",Long.class,symbol);if(assets.isEmpty())bad("Unknown affected asset");
            String key=event.get("eventKey").asText();String unique=key+"|"+symbol+"|"+impact.get("horizon").asText();if(!seen.add(unique))bad("Duplicate event impact");
            String group=SeasonalityService.hash(event.get("topic").asText()+"|"+event.get("kind").asText()+"|"+event.get("quote").asText().toLowerCase(Locale.ROOT).replaceAll("\\s+"," "));
            String direction=impact.get("direction").asText();int cap="SUMMARY_ONLY".equals(content.get("scope"))?25:35;
            int confidence=Math.min(cap,impact.get("interpretationConfidence").asInt());
            BigDecimal score=Set.of("UNKNOWN","MIXED").contains(direction)?null:BigDecimal.valueOf((0.75*impact.get("importance").asInt()+0.25*impact.get("novelty").asInt())*impact.get("relevance").asInt()/100.0*confidence/100.0*(direction.equals("NEGATIVE")?-1:1)).setScale(4,RoundingMode.HALF_UP);
            SqlIds.insert(db,"INSERT INTO information_ai_impacts(analysis_id,event_key,event_group,asset_id,horizon,direction,proposed_score,explanation,exact_quote) VALUES (?,?,?,?,?,?,?,?,?)",id,key,group,assets.getFirst(),impact.get("horizon").asText(),direction,score,event.get("explanation").asText(),event.get("quote").asText());
        }
        db.update("UPDATE information_ai_jobs SET status='SUCCEEDED',error_code=NULL,lease_until=NULL,updated_time=? WHERE id=?",ts(clock.instant()),jobId);return id;
    }
    @Transactional public void heartbeat(long id,String lease){work.locked(id,lease);db.update("UPDATE information_ai_jobs SET lease_until=?,updated_time=? WHERE id=?",ts(clock.instant().plusSeconds(900)),ts(clock.instant()),id);}
    @Transactional public void review(long id,Review review){
        var rows=db.queryForList("SELECT * FROM information_ai_analyses WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown analysis");
        if(review==null||review.reason()==null||review.reason().isBlank()||review.reason().length()>1000||review.eventGroups()==null)bad("Review rationale and event groups required");
        if(!"PROPOSED".equals(rows.getFirst().get("review_status")))throw new ResponseStatusException(CONFLICT,"Review is immutable; create a new analysis version");
        var keys=db.queryForList("SELECT DISTINCT event_key FROM information_ai_impacts WHERE analysis_id=?",String.class,id);
        for(var entry:review.eventGroups().entrySet())if(!keys.contains(entry.getKey())||entry.getValue()==null||!entry.getValue().matches("[A-Za-z0-9._-]{1,160}"))bad("Invalid event grouping");
        for(var entry:review.eventGroups().entrySet())db.update("UPDATE information_ai_impacts SET event_group=? WHERE analysis_id=? AND event_key=?",entry.getValue(),id,entry.getKey());
        db.update("UPDATE information_ai_analyses SET review_status=?,review_reason=?,reviewed_at=? WHERE id=?",review.approved()?"APPROVED":"REJECTED",review.reason(),ts(clock.instant()),id);
    }
    public List<Map<String,Object>> analyses(long item){return db.queryForList("SELECT a.*,j.model,j.prompt_version,c.scope,c.content_url FROM information_ai_analyses a JOIN information_ai_jobs j ON j.id=a.job_id JOIN information_contents c ON c.id=a.content_id WHERE j.item_id=? ORDER BY a.id DESC",item);}
    public Map<String,Object> asset(String symbol,String horizon){
        if(!Set.of("DAY","MONTH","YEAR").contains(horizon))bad("Invalid horizon");var ids=db.queryForList("SELECT id FROM assets WHERE symbol=?",Long.class,symbol.toUpperCase(Locale.ROOT));if(ids.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown asset");
        var rows=db.queryForList("SELECT p.*,a.review_status,a.created_time,a.reviewed_at,j.model,j.prompt_version,i.original_url,i.published_at,i.collected_at,i.external_key,s.publisher_group,c.scope,c.content_hash FROM information_ai_impacts p JOIN information_ai_analyses a ON a.id=p.analysis_id JOIN information_ai_jobs j ON j.id=a.job_id JOIN information_items i ON i.id=j.item_id JOIN information_sources s ON s.code=i.source_code JOIN information_contents c ON c.id=a.content_id WHERE p.asset_id=? AND p.horizon=? AND i.published_at<=? AND i.collected_at<=? ORDER BY a.id DESC,p.id",ids.getFirst(),horizon,ts(clock.instant()),ts(clock.instant()));
        var groups=new LinkedHashMap<String,BigDecimal>();Set<String> evidenceVersions=new HashSet<>(),conflicting=new HashSet<>();
        long halfLife=switch(horizon){case "DAY"->86400;case "MONTH"->2592000;default->31536000;};
        for(var row:rows)if("APPROVED".equals(row.get("review_status"))&&row.get("proposed_score")!=null){
            // Same content under different model/prompt versions must not be added twice.
            String version=row.get("event_group")+"|"+row.get("content_hash");if(!evidenceVersions.add(version))continue;
            double age=Math.max(0,Duration.between(SqlIds.instant(row.get("published_at")),clock.instant()).toSeconds());
            BigDecimal score=((BigDecimal)row.get("proposed_score")).multiply(BigDecimal.valueOf(Math.pow(.5,age/halfLife)));
            // For repeated coverage of one event use the strongest magnitude, not a sum.
            String group=(String)row.get("event_group");if(conflicting.contains(group))continue;
            if(groups.containsKey(group)&&groups.get(group).signum()*score.signum()<0){groups.remove(group);conflicting.add(group);}
            else groups.merge(group,score,(x,y)->x.abs().compareTo(y.abs())>=0?x:y);
        }
        BigDecimal total=groups.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).max(new BigDecimal("-100")).min(new BigDecimal("100")).setScale(4,RoundingMode.HALF_UP);
        return Map.of("symbol",symbol.toUpperCase(Locale.ROOT),"horizon",horizon,"proposedEvidence",rows,"approvedEventCount",groups.size(),"conflictingEventCount",conflicting.size(),"approvedScore",total,"attention",attention(rows),"method","AI_INFORMATION_V1_EXPERIMENTAL","limitations",List.of("Interpretation confidence is not a gain probability","Publisher count is not independently verified corroboration","Attention does not establish direction or a future price move","Same event across social and AI pipelines still requires cross-pipeline review"));
    }
    private Map<String,Object> attention(List<Map<String,Object>> rows){Set<String> recent=new HashSet<>(),previous=new HashSet<>(),publishers=new HashSet<>(),groups=new HashSet<>();Instant now=clock.instant();
        for(var r:rows){Instant at=SqlIds.instant(r.get("published_at"));String key=r.get("publisher_group")+"|"+r.get("external_key");if(!at.isBefore(now.minusSeconds(86400))){recent.add(key);publishers.add((String)r.get("publisher_group"));groups.add((String)r.get("event_group"));}else if(!at.isBefore(now.minusSeconds(29*86400L)))previous.add(key);}
        var result=new LinkedHashMap<String,Object>();result.put("publications24h",recent.size());result.put("publisherGroups24h",publishers.size());result.put("suggestedEventGroups24h",groups.size());result.put("previous28DaysDailyMean",previous.size()/28.0);result.put("relativeAttention",previous.isEmpty()?null:recent.size()*28.0/previous.size());result.put("coverage","ANALYZED_PUBLICATIONS_ONLY");return result;
    }
    private void validate(JsonNode a,String text){
        exact(a,"summary","uncertainties","events");str(a,"summary",4000);if(!a.get("uncertainties").isArray()||a.get("uncertainties").size()>100||!a.get("events").isArray()||a.get("events").size()>300)bad("Invalid result arrays");for(JsonNode u:a.get("uncertainties"))if(!u.isTextual()||u.asText().length()>1000)bad("Invalid uncertainty");
        for(JsonNode e:a.get("events")){
            exact(e,"eventKey","title","explanation","quote","speaker","topic","kind","impacts");str(e,"eventKey",160);str(e,"title",300);str(e,"explanation",2000);str(e,"quote",2000);
            if(e.get("quote").asText().length()<10||!text.contains(e.get("quote").asText()))bad("Quote absent from source");
            if(!e.get("speaker").isNull())str(e,"speaker",160);choice(e,"topic",Set.of("TARIFFS","MONETARY_POLICY","CORPORATE","CRYPTO","GENERAL"));choice(e,"kind",Set.of("ANNOUNCEMENT","RUMOR","OPINION","DENIAL"));
            if(!e.get("impacts").isArray()||e.get("impacts").size()>20)bad("Invalid impacts");
            for(JsonNode i:e.get("impacts")){exact(i,"symbol","direction","horizon","importance","relevance","novelty","interpretationConfidence");str(i,"symbol",40);choice(i,"direction",Set.of("POSITIVE","NEGATIVE","MIXED","UNKNOWN"));choice(i,"horizon",Set.of("DAY","MONTH","YEAR"));for(String k:List.of("importance","relevance","novelty","interpretationConfidence"))if(!i.get(k).isIntegralNumber()||!i.get(k).canConvertToInt()||i.get(k).asInt()<0||i.get(k).asInt()>100)bad("Invalid impact range");}
        }
    }
    private static void exact(JsonNode n,String... keys){if(n==null||!n.isObject())bad("Object required");Set<String> actual=new HashSet<>();n.fieldNames().forEachRemaining(actual::add);if(!actual.equals(Set.of(keys)))bad("Unexpected or missing result fields");}
    private static void str(JsonNode n,String k,int max){if(!n.get(k).isTextual()||n.get(k).asText().isBlank()||n.get(k).asText().length()>max)bad("Invalid "+k);}
    private static void choice(JsonNode n,String k,Set<String> choices){if(!n.get(k).isTextual()||!choices.contains(n.get(k).asText()))bad("Invalid "+k);}
    private JsonNode read(String s){try{return json.readTree(s);}catch(Exception e){throw new IllegalArgumentException("Invalid stored JSON");}}
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
    private static void bad(String s){throw new ResponseStatusException(BAD_REQUEST,s);}
}
