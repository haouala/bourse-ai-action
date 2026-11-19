package lu.bourse.engine.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.portfolio.PortfolioService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static lu.bourse.engine.social.SocialScoring.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class SocialSignalService {
    private final JdbcTemplate db;private final Clock clock;private final ObjectMapper json;private final PortfolioService portfolios;
    public SocialSignalService(JdbcTemplate db,Clock clock,ObjectMapper json,PortfolioService portfolios){this.db=db;this.clock=clock;this.json=json;this.portfolios=portfolios;}
    public record Actor(String displayName,String roleLabel,String platform,String accountId,boolean identityConfirmed,String identityEvidenceUrl){}
    public record Profile(Topic topic,int authorityScore,Instant validFrom,Instant validUntil,String rationale){}
    public record Publication(long actorId,String externalId,String content,String sourceUrl,Instant publishedAt){}
    public record Event(String canonicalKey,String title,Topic topic,Kind kind,Confirmation confirmation,String evidenceUrl,List<Long> publicationIds){}
    public record Assessment(String symbol,Direction direction,int materiality,int relevance,int novelty,int analystConfidence,Horizon horizon,String rationale){}
    public record Revision(Confirmation confirmation,String evidenceUrl,boolean retracted){}
    public record Score(long id,long eventId,String symbol,Direction direction,Result result,String method,boolean reviewOnly,Instant scoredAt){}

    @Transactional public long actor(Actor a){
        text(a.displayName(),160);text(a.roleLabel(),160);text(a.accountId(),100);
        if(!Set.of("X","TRUTH_SOCIAL","LINKEDIN","YOUTUBE","OFFICIAL_WEB","OTHER").contains(a.platform()))bad("Unsupported platform");
        if(a.platform().equals("X")&&!a.accountId().matches("[0-9]{1,19}"))bad("X requires the numeric account ID");
        if(a.identityConfirmed())url(a.identityEvidenceUrl());
        return SqlIds.insert(db,"INSERT INTO social_actors(display_name,role_label,platform,account_id,identity_confirmed,identity_evidence_url) VALUES (?,?,?,?,?,?)",a.displayName(),a.roleLabel(),a.platform(),a.accountId(),a.identityConfirmed(),a.identityEvidenceUrl());
    }
    @Transactional public long profile(long actorId,Profile p){
        actorRow(actorId,true);required(p.topic());range(p.authorityScore());text(p.rationale(),1500);
        if(p.validFrom()==null||(p.validUntil()!=null&&!p.validUntil().isAfter(p.validFrom())))bad("Invalid influence validity interval");
        var end=p.validUntil()==null?Instant.parse("9999-01-01T00:00:00Z"):p.validUntil();
        int count=db.queryForObject("SELECT COUNT(*) FROM actor_influence_profiles WHERE actor_id=? AND topic=? AND valid_from<? AND (valid_until IS NULL OR valid_until>?)",Integer.class,actorId,p.topic().name(),ts(end),ts(p.validFrom()));
        if(count>0)throw new ResponseStatusException(CONFLICT,"Overlapping influence profiles");
        return SqlIds.insert(db,"INSERT INTO actor_influence_profiles(actor_id,topic,authority_score,valid_from,valid_until,rationale) VALUES (?,?,?,?,?,?)",actorId,p.topic().name(),p.authorityScore(),ts(p.validFrom()),p.validUntil()==null?null:ts(p.validUntil()),p.rationale());
    }
    @Transactional public long publication(Publication p){
        actorRow(p.actorId(),true);text(p.externalId(),100);text(p.content(),30000);url(p.sourceUrl());
        if(p.publishedAt()==null||p.publishedAt().isAfter(clock.instant()))bad("Publication time must not be in the future");
        var old=db.queryForList("SELECT * FROM social_publications WHERE actor_id=? AND external_id=?",p.actorId(),p.externalId());
        if(!old.isEmpty()){
            var r=old.getFirst();
            if(!r.get("content").equals(p.content())||!r.get("source_url").equals(p.sourceUrl())||!SqlIds.instant(r.get("published_at")).equals(p.publishedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)))throw new ResponseStatusException(CONFLICT,"Publication changed: retain the original and submit a new revision ID");
            return ((Number)r.get("id")).longValue();
        }
        return SqlIds.insert(db,"INSERT INTO social_publications(actor_id,external_id,content,source_url,published_at,collected_at) VALUES (?,?,?,?,?,?)",p.actorId(),p.externalId(),p.content(),p.sourceUrl(),ts(p.publishedAt()),ts(clock.instant()));
    }
    @Transactional public long event(Event e){
        text(e.canonicalKey(),180);text(e.title(),300);required(e.topic());required(e.kind());required(e.confirmation());url(e.evidenceUrl());
        if(e.publicationIds()==null||e.publicationIds().isEmpty()||e.publicationIds().size()>100)bad("An event needs 1 to 100 source publication IDs");
        var ids=new TreeSet<>(e.publicationIds());
        for(Long id:ids)if(id==null||db.queryForObject("SELECT COUNT(*) FROM social_publications WHERE id=?",Integer.class,id)==0)bad("Unknown source publication");
        var old=db.queryForList("SELECT * FROM social_events WHERE canonical_key=? FOR UPDATE",e.canonicalKey());
        if(!old.isEmpty()){
            var r=old.getFirst();long id=((Number)r.get("id")).longValue();
            var linked=new TreeSet<>(db.queryForList("SELECT publication_id FROM social_event_publications WHERE event_id=?",Long.class,id));
            if(!r.get("title").equals(e.title())||!r.get("topic").equals(e.topic().name())||!r.get("event_kind").equals(e.kind().name())||!r.get("confirmation").equals(e.confirmation().name())||!r.get("evidence_url").equals(e.evidenceUrl())||!linked.equals(ids))throw new ResponseStatusException(CONFLICT,"Existing canonical event: use explicit revision or publication linking");
            return id;
        }
        long id=SqlIds.insert(db,"INSERT INTO social_events(canonical_key,title,topic,event_kind,confirmation,evidence_url) VALUES (?,?,?,?,?,?)",e.canonicalKey(),e.title(),e.topic().name(),e.kind().name(),e.confirmation().name(),e.evidenceUrl());
        for(long p:ids)db.update("INSERT INTO social_event_publications(event_id,publication_id) VALUES (?,?)",id,p);
        return id;
    }
    @Transactional public void link(long eventId,long publicationId){
        eventRow(eventId);if(db.queryForObject("SELECT COUNT(*) FROM social_publications WHERE id=?",Integer.class,publicationId)==0)bad("Unknown publication");
        if(db.queryForObject("SELECT COUNT(*) FROM social_event_publications WHERE event_id=? AND publication_id=?",Integer.class,eventId,publicationId)==0){
            db.update("INSERT INTO social_event_publications(event_id,publication_id) VALUES (?,?)",eventId,publicationId);
            db.update("UPDATE social_events SET revision=revision+1,updated_time=? WHERE id=?",ts(clock.instant()),eventId);
            rescore(eventId);
        }
    }
    @Transactional public void revise(long id,Revision r){
        eventRow(id);required(r.confirmation());url(r.evidenceUrl());
        db.update("UPDATE social_events SET confirmation=?,evidence_url=?,retracted=?,revision=revision+1,updated_time=? WHERE id=?",r.confirmation().name(),r.evidenceUrl(),r.retracted(),ts(clock.instant()),id);
        rescore(id);
        // The previous alert is retained for audit; a correction has a separate deduplicated alert.
        if(r.retracted())notifyPositions(id,null,"SOCIAL_EVENT_CORRECTION","Correction/retrait de l'événement social "+id+". Réexaminer l'analyse précédente.",clock.instant());
    }
    @Transactional public Score assess(long eventId,Assessment a){
        var e=eventRow(eventId);required(a.direction());required(a.horizon());range(a.materiality());range(a.relevance());range(a.novelty());range(a.analystConfidence());text(a.rationale(),2000);text(a.symbol(),40);
        var assets=db.queryForList("SELECT id FROM assets WHERE symbol=?",Long.class,a.symbol().toUpperCase(Locale.ROOT));
        if(assets.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown asset");
        long assetId=assets.getFirst();var old=db.queryForList("SELECT id FROM social_asset_assessments WHERE event_id=? AND asset_id=?",Long.class,eventId,assetId);
        long id;
        if(old.isEmpty())id=SqlIds.insert(db,"INSERT INTO social_asset_assessments(event_id,asset_id,direction,materiality,relevance,novelty,analyst_confidence,horizon,rationale,assessed_at) VALUES (?,?,?,?,?,?,?,?,?,?)",eventId,assetId,a.direction().name(),a.materiality(),a.relevance(),a.novelty(),a.analystConfidence(),a.horizon().name(),a.rationale(),ts(clock.instant()));
        else{id=old.getFirst();db.update("UPDATE social_asset_assessments SET direction=?,materiality=?,relevance=?,novelty=?,analyst_confidence=?,horizon=?,rationale=?,assessed_at=? WHERE id=?",a.direction().name(),a.materiality(),a.relevance(),a.novelty(),a.analystConfidence(),a.horizon().name(),a.rationale(),ts(clock.instant()),id);}
        return score(e,id);
    }
    @Transactional public List<Score> rescore(long eventId){
        var e=eventRow(eventId);var results=new ArrayList<Score>();
        for(long id:db.queryForList("SELECT id FROM social_asset_assessments WHERE event_id=? ORDER BY id",Long.class,eventId))results.add(score(e,id));
        return results;
    }
    private Score score(Map<String,Object> e,long assessmentId){
        long eventId=((Number)e.get("id")).longValue();Instant now=clock.instant();
        var a=db.queryForMap("SELECT x.*,a.symbol FROM social_asset_assessments x JOIN assets a ON a.id=x.asset_id WHERE x.id=?",assessmentId);
        var sources=db.queryForList("SELECT p.id,p.published_at,p.collected_at,p.source_url,p.retracted,a.id AS actor_id,a.identity_confirmed,a.identity_evidence_url,a.display_name,a.role_label FROM social_event_publications l JOIN social_publications p ON p.id=l.publication_id JOIN social_actors a ON a.id=p.actor_id WHERE l.event_id=? ORDER BY p.published_at,p.id",eventId);
        int authority=0;boolean verified=false;Instant published=null;
        var authorityInputs=new ArrayList<Map<String,Object>>();
        for(var s:sources){
            Instant t=SqlIds.instant(s.get("published_at"));
            if((Boolean)s.get("retracted"))continue;
            if(published==null||t.isBefore(published))published=t;
            var profiles=db.queryForList("SELECT id,authority_score,rationale,valid_from,valid_until FROM actor_influence_profiles WHERE actor_id=? AND topic=? AND valid_from<=? AND (valid_until IS NULL OR valid_until>?)",s.get("actor_id"),e.get("topic"),ts(t),ts(t));
            int rank=profiles.isEmpty()?0:((Number)profiles.getFirst().get("authority_score")).intValue();
            boolean confirmed=(Boolean)s.get("identity_confirmed");
            // Maximum, never sum: repeated posts and syndication do not multiply influence.
            if(rank>authority||(rank==authority&&confirmed)){authority=rank;verified=confirmed;}
            var input=lowerKeys(s);input.put("applicableProfiles",profiles.stream().map(SocialSignalService::lowerKeys).toList());authorityInputs.add(input);
        }
        boolean retracted=(Boolean)e.get("retracted")||published==null;
        if(published==null)published=now;
        var direction=Direction.valueOf((String)a.get("direction"));
        Result r=compute(authority,num(a,"materiality"),num(a,"relevance"),num(a,"novelty"),num(a,"analyst_confidence"),Confirmation.valueOf((String)e.get("confirmation")),Kind.valueOf((String)e.get("event_kind")),verified,retracted,direction,Horizon.valueOf((String)a.get("horizon")),published,now);
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("event",lowerKeys(e));snapshot.put("assessment",lowerKeys(a));snapshot.put("sources",authorityInputs);snapshot.put("publishedAt",published.toString());snapshot.put("scoredAt",now.toString());snapshot.put("model",METHOD);snapshot.put("weights",Map.of("authority",0.25,"materiality",0.50,"novelty",0.25));
        String serialized;try{serialized=json.writeValueAsString(snapshot);}catch(Exception ex){throw new IllegalStateException("Cannot serialize score audit",ex);}
        long id=SqlIds.insert(db,"INSERT INTO social_impact_scores(assessment_id,event_revision,scored_at,authority_score,potential_impact,confidence_score,signed_score,method,input_snapshot) VALUES (?,?,?,?,?,?,?,?,?)",assessmentId,e.get("revision"),ts(now),r.authority(),r.potential(),r.confidence(),r.signedScore(),METHOD,serialized);
        if(r.potential()>=70&&r.confidence()>=50)notifyPositions(eventId,((Number)a.get("asset_id")).longValue(),"SOCIAL_EVENT_REVIEW","Événement social "+eventId+" sur "+a.get("symbol")+" : impact "+Math.round(r.potential())+"/100, confiance "+r.confidence()+"/100, direction "+direction+". Analyse à revoir ; aucun ordre automatique.",published);
        return new Score(id,eventId,(String)a.get("symbol"),direction,r,METHOD,true,now);
    }
    private void notifyPositions(long eventId,Long assetId,String type,String message,Instant dataTime){
        var matches=assetId==null?db.queryForList("SELECT DISTINCT p.id,p.portfolio_id FROM positions p JOIN social_asset_assessments a ON a.asset_id=p.asset_id WHERE a.event_id=? AND p.status='OPEN' ORDER BY p.portfolio_id,p.id",eventId):db.queryForList("SELECT id,portfolio_id FROM positions WHERE asset_id=? AND status='OPEN' ORDER BY portfolio_id,id",assetId);
        for(var p:matches){long portfolio=((Number)p.get("portfolio_id")).longValue(),position=((Number)p.get("id")).longValue();
            db.queryForObject("SELECT id FROM portfolios WHERE id=? FOR UPDATE",Long.class,portfolio);
            portfolios.alert(portfolio,position,type,type+":"+eventId+":"+position,message,dataTime);
        }
    }
    public List<Map<String,Object>> actors(){return db.queryForList("SELECT id,display_name,role_label,platform,account_id,identity_confirmed,identity_evidence_url,last_sync_at,created_time,updated_time FROM social_actors ORDER BY id");}
    public List<Map<String,Object>> publications(){return db.queryForList("SELECT * FROM social_publications ORDER BY collected_at DESC,id DESC LIMIT 200");}
    public List<Map<String,Object>> events(){return db.queryForList("SELECT * FROM social_events ORDER BY id DESC LIMIT 200");}
    public List<Map<String,Object>> scores(long id){eventExists(id);return db.queryForList("SELECT s.*,a.asset_id,x.symbol,a.direction FROM social_impact_scores s JOIN social_asset_assessments a ON a.id=s.assessment_id JOIN assets x ON x.id=a.asset_id WHERE a.event_id=? ORDER BY s.id DESC LIMIT 200",id);}
    Map<String,Object> actorRow(long id,boolean lock){var rows=db.queryForList("SELECT * FROM social_actors WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown social actor");return rows.getFirst();}
    private void eventExists(long id){if(db.queryForObject("SELECT COUNT(*) FROM social_events WHERE id=?",Integer.class,id)==0)throw new ResponseStatusException(NOT_FOUND,"Unknown event");}
    private Map<String,Object> eventRow(long id){var rows=db.queryForList("SELECT * FROM social_events WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown event");return rows.getFirst();}
    static Timestamp ts(Instant i){return Timestamp.from(i);}
    private static LinkedHashMap<String,Object> lowerKeys(Map<String,Object> source){var result=new LinkedHashMap<String,Object>();source.forEach((k,v)->result.put(k.toLowerCase(Locale.ROOT),v));return result;}
    private static int num(Map<String,Object> m,String key){return ((Number)m.get(key)).intValue();}
    static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max)bad("Required text exceeds bounds or is blank");}
    static void url(String s){text(s,1500);try{URI u=URI.create(s);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)bad("Evidence/source URL must be HTTPS");}catch(IllegalArgumentException e){bad("Invalid source URL");}}
    private static void required(Object o){if(o==null)bad("Missing classification field");}
    private static void range(int n){if(n<0||n>100)bad("Scores must be between 0 and 100");}
    static void bad(String message){throw new ResponseStatusException(BAD_REQUEST,message);}
}
