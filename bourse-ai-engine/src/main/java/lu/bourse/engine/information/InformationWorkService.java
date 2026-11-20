package lu.bourse.engine.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.seasonality.SeasonalityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Persistent work queue. External calls always happen outside database transactions. */
@Service
public class InformationWorkService {
    public record Segment(String speaker,Double startSeconds,Double endSeconds,String text){}
    public record Content(String text,String scope,String url,String method,String error,List<Segment> segments){}
    public record Failure(String code,boolean retryable){}
    private final JdbcTemplate db;private final Clock clock;private final ObjectMapper json;
    private final boolean enabled;private final String model,prompt;
    public InformationWorkService(JdbcTemplate db,Clock clock,ObjectMapper json,
        @Value("${information.ai.enabled:false}")boolean enabled,
        @Value("${information.ai.model:gpt-5.4-mini-2026-03-17}")String model,
        @Value("${information.ai.prompt-version:information-v1}")String prompt){
        this.db=db;this.clock=clock;this.json=json;this.enabled=enabled;this.model=model;this.prompt=prompt;
        if(!model.matches("[A-Za-z0-9._-]{1,100}")||!prompt.matches("[A-Za-z0-9._-]{1,80}"))throw new IllegalArgumentException("Invalid information model configuration");
    }
    @Transactional public int enqueue(){
        if(!enabled)throw new ResponseStatusException(CONFLICT,"Information AI pipeline disabled");
        guard();int count=0;
        for(long item:db.queryForList("SELECT i.id FROM information_items i JOIN information_sources s ON s.code=i.source_code WHERE s.enabled=TRUE AND i.review_status='PENDING_REVIEW' AND i.published_at<=? ORDER BY i.id",Long.class,ts(clock.instant()))) {
            if(db.queryForObject("SELECT COUNT(*) FROM information_ai_jobs WHERE item_id=? AND model=? AND prompt_version=?",Integer.class,item,model,prompt)==0){
                db.update("INSERT INTO information_ai_jobs(item_id,model,prompt_version,next_attempt_at,created_time,updated_time) VALUES (?,?,?,?,?,?)",item,model,prompt,ts(clock.instant()),ts(clock.instant()),ts(clock.instant()));count++;
            }
        }
        return count;
    }
    @Transactional public Map<String,Object> claim(){
        if(!enabled)throw new ResponseStatusException(CONFLICT,"Information AI pipeline disabled");
        guard();Instant now=clock.instant();
        db.update("UPDATE information_ai_jobs SET status='FAILED',error_code='ATTEMPTS_EXHAUSTED',lease_token=NULL,lease_until=NULL,updated_time=? WHERE status='RUNNING' AND lease_until<=? AND attempts>=3",ts(now),ts(now));
        var jobs=db.queryForList("SELECT j.* FROM information_ai_jobs j JOIN information_items i ON i.id=j.item_id JOIN information_sources s ON s.code=i.source_code WHERE j.model=? AND j.prompt_version=? AND s.enabled=TRUE AND j.attempts<3 AND ((j.status='PENDING' AND j.next_attempt_at<=?) OR (j.status='RUNNING' AND j.lease_until<=?)) ORDER BY j.id LIMIT 1",model,prompt,ts(now),ts(now));
        if(jobs.isEmpty())return Map.of("status","IDLE");var job=jobs.getFirst();long id=((Number)job.get("id")).longValue();String token=UUID.randomUUID().toString();
        db.update("UPDATE information_ai_jobs SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,error_code=NULL,updated_time=? WHERE id=?",token,ts(now.plusSeconds(900)),ts(now),id);
        var item=db.queryForMap("SELECT i.*,s.content_kind,s.feed_url FROM information_items i JOIN information_sources s ON s.code=i.source_code WHERE i.id=?",job.get("item_id"));
        var result=new LinkedHashMap<String,Object>();result.put("status","RUNNING");result.put("jobId",id);result.put("leaseToken",token);result.put("model",model);result.put("promptVersion",prompt);result.put("item",item);
        result.put("content",job.get("content_id")==null?null:db.queryForMap("SELECT * FROM information_contents WHERE id=?",job.get("content_id")));
        result.put("assets",db.queryForList("SELECT symbol,name FROM assets ORDER BY symbol"));return result;
    }
    @Transactional public long content(long jobId,String token,Content c){
        var job=locked(jobId,token);validate(c);
        var item=db.queryForMap("SELECT * FROM information_items WHERE id=?",job.get("item_id"));
        if(c.scope().equals("SUMMARY_ONLY")&&!c.text().equals(item.get("title")+"\n"+item.get("summary")))bad("Summary fallback must preserve collected text");
        String segments=write(c.segments());String hash=SeasonalityService.hash(c.scope()+"\n"+c.url()+"\n"+c.text()+"\n"+segments);
        if(job.get("content_id")!=null){var old=db.queryForMap("SELECT id,content_hash FROM information_contents WHERE id=?",job.get("content_id"));if(!hash.equals(old.get("content_hash")))throw new ResponseStatusException(CONFLICT,"Content already fixed for this job");return ((Number)old.get("id")).longValue();}
        var old=db.queryForList("SELECT id FROM information_contents WHERE item_id=? AND content_hash=?",Long.class,job.get("item_id"),hash);
        long id=old.isEmpty()?SqlIds.insert(db,"INSERT INTO information_contents(item_id,content_hash,body_text,scope,content_url,extraction_method,fetch_error,segments_json,fetched_at) VALUES (?,?,?,?,?,?,?,?,?)",job.get("item_id"),hash,c.text(),c.scope(),c.url(),c.method(),c.error(),segments,ts(clock.instant())):old.getFirst();
        db.update("UPDATE information_ai_jobs SET content_id=?,updated_time=? WHERE id=?",id,ts(clock.instant()),jobId);return id;
    }
    @Transactional public void fail(long id,String token,Failure f){var job=locked(id,token);if(f==null||f.code()==null||!f.code().matches("[A-Z0-9_]{1,80}"))bad("Invalid error code");boolean retry=f.retryable()&&((Number)job.get("attempts")).intValue()<3;
        db.update("UPDATE information_ai_jobs SET status=?,error_code=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,updated_time=? WHERE id=?",retry?"PENDING":"FAILED",f.code(),ts(clock.instant().plusSeconds(3600)),ts(clock.instant()),id);}
    public List<Map<String,Object>> jobs(){return db.queryForList("SELECT id,item_id,model,prompt_version,status,attempts,error_code,created_time,updated_time FROM information_ai_jobs ORDER BY id DESC LIMIT 200");}
    public List<Map<String,Object>> contents(long item){return db.queryForList("SELECT * FROM information_contents WHERE item_id=? ORDER BY id DESC",item);}
    public Map<String,Object> locked(long id,String token){var rows=db.queryForList("SELECT * FROM information_ai_jobs WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown job");var job=rows.getFirst();if(token==null||!token.equals(job.get("lease_token"))||!"RUNNING".equals(job.get("status"))||!SqlIds.instant(job.get("lease_until")).isAfter(clock.instant()))throw new ResponseStatusException(CONFLICT,"Lease expired or superseded");return job;}
    private void guard(){db.queryForObject("SELECT id FROM information_worker_guard WHERE id=1 FOR UPDATE",Integer.class);}
    private void validate(Content c){
        if(c==null||c.text()==null||c.text().isBlank()||c.text().length()>200000||c.url()==null||c.scope()==null||!Set.of("SUMMARY_ONLY","ARTICLE_EXTRACT","TRANSCRIPT","PARTIAL_TRANSCRIPT").contains(c.scope())||c.method()==null||!c.method().matches("[A-Z0-9_]{1,80}")||c.segments()==null||c.segments().size()>2000||c.error()!=null&&!c.error().matches("[A-Z0-9_]{1,80}"))bad("Invalid extracted content");
        try{var u=URI.create(c.url());if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||c.url().length()>1500)bad("Invalid content URL");}catch(IllegalArgumentException e){bad("Invalid content URL");}
        for(var s:c.segments())if(s==null||s.text()==null||s.text().isBlank()||!c.text().contains(s.text())||s.speaker()!=null&&s.speaker().length()>160||(s.startSeconds()==null)!=(s.endSeconds()==null)||s.startSeconds()!=null&&(!Double.isFinite(s.startSeconds())||!Double.isFinite(s.endSeconds())||s.startSeconds()<0||s.endSeconds()<s.startSeconds()))bad("Invalid transcript evidence");
    }
    private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON");}}
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
    private static void bad(String text){throw new ResponseStatusException(BAD_REQUEST,text);}
}
