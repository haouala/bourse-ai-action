package lu.bourse.engine.social;

import com.fasterxml.jackson.databind.JsonNode;
import lu.bourse.engine.persistence.SqlIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Explicitly invoked, read-only X API adapter. No scheduler, posting or account discovery. */
@Service
public class XPublicationCollector {
    private static final int PAGE_SIZE=5;
    private final JdbcTemplate db;private final SocialSignalService social;private final Clock clock;private final TransactionTemplate tx;
    private final boolean enabled;private final int monthlyLimit;private final String token,baseUrl;private final RestClient http;
    public XPublicationCollector(JdbcTemplate db,SocialSignalService social,Clock clock,PlatformTransactionManager manager,
        @Value("${social.x.enabled:false}") boolean enabled,@Value("${social.x.monthly-post-limit:0}") int monthlyLimit,
        @Value("${social.x.bearer-token:}") String token,@Value("${social.x.base-url:https://api.x.com}") String baseUrl){
        this.db=db;this.social=social;this.clock=clock;this.enabled=enabled;this.monthlyLimit=monthlyLimit;this.token=token;this.baseUrl=baseUrl;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(Duration.ofSeconds(20));http=RestClient.builder().requestFactory(factory).build();
    }
    public Map<String,Object> sync(long actorId){
        if(!enabled||token.isBlank()||monthlyLimit<PAGE_SIZE)throw new ResponseStatusException(SERVICE_UNAVAILABLE,"X collection disabled: explicit token and monthly post allowance required");
        var actor=social.actorRow(actorId,false);
        if(!"X".equals(actor.get("platform"))||!(Boolean)actor.get("identity_confirmed"))throw new ResponseStatusException(BAD_REQUEST,"X collection requires a confirmed X account");
        // Commit before network I/O: failures/restarts cannot reset the conservative allowance.
        long reservation=Objects.requireNonNull(tx.execute(status->{
            db.queryForObject("SELECT id FROM social_api_guard WHERE id=1 FOR UPDATE",Integer.class);
            String month=YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).toString();
            long reserved=db.queryForObject("SELECT COALESCE(SUM(reserved_posts),0) FROM social_api_reservations WHERE month_key=?",Long.class,month);
            if(reserved+PAGE_SIZE>monthlyLimit)throw new ResponseStatusException(TOO_MANY_REQUESTS,"Local X monthly post allowance exhausted");
            return SqlIds.insert(db,"INSERT INTO social_api_reservations(actor_id,month_key,reserved_posts,reserved_at) VALUES (?,?,?,?)",actorId,month,PAGE_SIZE,SocialSignalService.ts(clock.instant()));
        }));
        return tx.execute(status->{
            var row=social.actorRow(actorId,true);
            var builder=UriComponentsBuilder.fromUriString(baseUrl).path("/2/users/{id}/tweets").queryParam("max_results",PAGE_SIZE).queryParam("exclude","retweets").queryParam("post.fields","created_at");
            if(row.get("last_post_id")!=null)builder.queryParam("since_id",row.get("last_post_id"));
            if(row.get("pending_page_token")!=null)builder.queryParam("pagination_token",row.get("pending_page_token"));
            URI uri=builder.buildAndExpand(row.get("account_id")).encode().toUri();
            JsonNode body;
            try{
                var response=http.get().uri(uri).headers(h->h.setBearerAuth(token)).retrieve().toEntity(JsonNode.class);
                if(!response.getStatusCode().is2xxSuccessful())throw new ResponseStatusException(BAD_GATEWAY,"X returned a non-success status");
                body=response.getBody();
            }catch(RestClientResponseException ex){
                throw new ResponseStatusException(ex.getStatusCode().value()==429?TOO_MANY_REQUESTS:BAD_GATEWAY,"X request failed (HTTP "+ex.getStatusCode().value()+"); allowance reservation retained");
            }catch(org.springframework.web.client.RestClientException ex){throw new ResponseStatusException(BAD_GATEWAY,"X connection failed; allowance reservation retained");}
            if(body==null||body.has("errors"))throw new ResponseStatusException(BAD_GATEWAY,"X returned an incomplete response; cursor unchanged");
            var data=body.path("data");if(!data.isMissingNode()&&!data.isArray())throw new ResponseStatusException(BAD_GATEWAY,"Invalid X data shape");
            if(data.size()>PAGE_SIZE)throw new ResponseStatusException(BAD_GATEWAY,"X exceeded requested page size");
            String high=(String)row.get("pending_high_water_id");int count=0;
            for(JsonNode post:data){
                String id=post.path("id").asText(),text=post.path("text").asText();Instant published;
                try{if(!id.matches("[0-9]{1,19}"))throw new IllegalArgumentException();published=Instant.parse(post.path("created_at").asText());}catch(RuntimeException ex){throw new ResponseStatusException(BAD_GATEWAY,"X post missing valid ID or publication timestamp");}
                social.publication(new SocialSignalService.Publication(actorId,id,text,"https://x.com/i/web/status/"+id,published));
                if(high==null||new BigInteger(id).compareTo(new BigInteger(high))>0)high=id;count++;
            }
            String next=body.path("meta").path("next_token").asText(null);
            if(next!=null&&(next.isBlank()||next.length()>2000))throw new ResponseStatusException(BAD_GATEWAY,"Invalid X pagination token");
            if(next==null){db.update("UPDATE social_actors SET last_post_id=?,pending_page_token=NULL,pending_high_water_id=NULL,last_sync_at=?,updated_time=? WHERE id=?",high==null?row.get("last_post_id"):high,SocialSignalService.ts(clock.instant()),SocialSignalService.ts(clock.instant()),actorId);}
            else db.update("UPDATE social_actors SET pending_page_token=?,pending_high_water_id=?,last_sync_at=?,updated_time=? WHERE id=?",next,high,SocialSignalService.ts(clock.instant()),SocialSignalService.ts(clock.instant()),actorId);
            db.update("UPDATE social_api_reservations SET fetched_posts=? WHERE id=?",count,reservation);
            return Map.of("fetched",count,"morePages",next!=null,"classification","PENDING_REVIEW","reservedPosts",PAGE_SIZE);
        });
    }
}
