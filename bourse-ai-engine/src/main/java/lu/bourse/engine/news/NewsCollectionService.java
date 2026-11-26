package lu.bourse.engine.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.information.InformationService;
import lu.bourse.engine.persistence.SqlIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

/**
 * Three-source news architecture: GDELT NGrams + GKG bulk archives + existing RSS/Atom.
 * Reads official fixed hosts; no search API calls, no provider API keys.
 * Sources are independently checkpointed, bounded, and manually/automatically opt-in.
 */
@Service
public class NewsCollectionService {
    private static final Logger log=LoggerFactory.getLogger(NewsCollectionService.class);
    private static final Pattern SLOT=Pattern.compile("[0-9]{14}");
    private static final Pattern GKG_FILE=Pattern.compile("([0-9]{14}\\.gkg\\.csv\\.zip)");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InformationService rss;
    private final GdeltArchiveParser parser;
    private final HttpClient http;
    private final Clock clock;
    private final boolean scheduled;
    private final String gdeltBase;
    public record Run(String method,String batch,String status,int matched,int inserted,String message,String code,boolean retryable){}
    public NewsCollectionService(JdbcTemplate db,PlatformTransactionManager manager,
        InformationService rss,ObjectMapper json,Clock clock,
        @Value("${news.bulk.enabled:false}") boolean scheduled,
        @Value("${news.bulk.base-url:https://data.gdeltproject.org}") String gdeltBase) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.rss=rss;this.parser=new GdeltArchiveParser(json);
        this.clock=clock;this.scheduled=scheduled;this.gdeltBase=gdeltBase;
        if(!gdeltBase.equals("https://data.gdeltproject.org") &&
            !gdeltBase.matches("http://127\\.0\\.0\\.1:[0-9]{2,5}"))
            throw new IllegalArgumentException("GDELT bulk URL must be official HTTPS or local integration fixture");
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Map<String,Object> status() {
        return Map.of("automaticEnabled",scheduled,"methods",List.of("NGRAMS","GKG","RSS"),
            "maxArchiveCompressedBytes",30_000_000,
            "runs",db.queryForList("SELECT method,batch_key,status,attempts,inserted_count,matched_count,error_code,next_attempt_at FROM gdelt_bulk_runs ORDER BY id DESC LIMIT 30"),
            "note","NGrams and GKG contain article metadata/mentions, not publisher fulltext. RSS is collected by the existing InformationService.");
    }

    public Run run(String method,String slot) {
        return switch(method) {
            case "RSS" -> rssOnce();
            case "GKG" -> gkg(slot);
            case "NGRAMS" -> ngrams(slot);
            default -> throw new NewsIngestionException("NEWS_METHOD_INVALID",HttpStatus.BAD_REQUEST,false,0);
        };
    }

    private Run rssOnce() {
        var enabled=rss.sources().stream().filter(s->Boolean.TRUE.equals(s.get("enabled")))
            .filter(s->s.get("next_attempt_at")==null || !SqlIds.instant(s.get("next_attempt_at")).isAfter(clock.instant()))
            .findFirst();
        if(enabled.isEmpty()) return new Run("RSS","","IDLE",0,0,"Aucun flux RSS actif et prêt à être collecté.",null,false);
        String code=(String)enabled.get().get("code");
        log.info("news.collect method=RSS source={} status=STARTED",code);
        try {
            var response=rss.sync(code);
            int inserted=((Number)response.getOrDefault("inserted",0)).intValue();
            log.info("news.collect method=RSS source={} status=SUCCESS inserted={}",code,inserted);
            return new Run("RSS",code,"SUCCESS",inserted,inserted,"Flux RSS collecté.",null,false);
        } catch(Exception e) {
            log.warn("news.collect method=RSS source={} status=ERROR type={}",code,e.getClass().getSimpleName());
            throw new NewsIngestionException("RSS_COLLECT_FAILED",HttpStatus.BAD_GATEWAY,true,3600);
        }
    }

    private String latestGkgSlot() {
        String index=readSmallText(gdeltBase+"/gdeltv2/lastupdate.txt",16_000);
        Matcher file=GKG_FILE.matcher(index);
        if(!file.find())throw new NewsIngestionException("GKG_INDEX_UNAVAILABLE",HttpStatus.BAD_GATEWAY,true,900);
        return file.group(1).substring(0,14);
    }

    private void validateSlot(String batch) {
        if(batch==null || !SLOT.matcher(batch).matches())
            throw new NewsIngestionException("NEWS_SLOT_INVALID",HttpStatus.BAD_REQUEST,false,0);
        try {
            Instant timestamp=LocalDateTime.parse(batch,
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC);
            if(timestamp.isAfter(clock.instant().plusSeconds(120))||
                timestamp.isBefore(clock.instant().minus(Duration.ofDays(30))))
                throw new NewsIngestionException("NEWS_SLOT_OUT_OF_RANGE",HttpStatus.BAD_REQUEST,false,0);
        } catch(java.time.format.DateTimeParseException e) {
            throw new NewsIngestionException("NEWS_SLOT_INVALID",HttpStatus.BAD_REQUEST,false,0);
        }
    }

    private Run gkg(String slot) {
        String batch=slot==null || slot.isBlank()?latestGkgSlot():slot;
        validateSlot(batch);
        return collect("GKG",batch,()-> {
            try(var zip=fetch(gdeltBase+"/gdeltv2/"+batch+".gkg.csv.zip")) {
                return parser.gkg(zip,watchlist());
            }
        });
    }

    /**
     * Legacy GDELT NGrams commonly publishes minute bursts immediately after
     * a quarter-hour heartbeat. Select ONE safely aged candidate per tick.
     * Never assert that the candidate is necessarily published.
     */
    static String ngramCandidate(Instant now) {
        var ready=now.minus(Duration.ofMinutes(7)).atZone(ZoneOffset.UTC);
        int quarter=(ready.getMinute()/15)*15;
        var candidate=ready.withMinute(quarter).withSecond(0).withNano(0)
            .plusMinutes(1);
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .format(candidate);
    }

    private Run ngrams(String slot) {
        // Web NGrams is a minute-level stream, not the 15-minute GKG feed.
        // No official lastupdate manifest for NGrams is documented.
        // Probe ONE independent, safely delayed minute instead of inventing
        // four GKG-derived NGram archive URLs every scheduler tick.
        String batch=slot;
        if(batch==null || batch.isBlank()) {
            batch=ngramCandidate(clock.instant());
        }
        validateSlot(batch);
        final String key=batch;
        return collect("NGRAMS",key,()-> {
            try(var words=fetch(gdeltBase+"/gdeltv5/weblegacy/ngrams/"+key+".ngrams.txt.gz");
                var toc=fetch(gdeltBase+"/gdeltv5/weblegacy/ngrams/"+key+".toc.json.gz")) {
                return parser.ngrams(words,toc,watchlist());
            }
        });
    }
    private interface ArchiveJob {GdeltArchiveParser.Result parse() throws IOException;}

    private Run collect(String method,String batch,ArchiveJob job) {
        boolean claimed=Boolean.TRUE.equals(tx.execute(status->{
            db.queryForObject("SELECT id FROM gdelt_bulk_guard WHERE id=1 FOR UPDATE",Integer.class);
            Instant now=clock.instant();
            var existing=db.queryForList("SELECT id,status,lease_until,next_attempt_at,attempts FROM gdelt_bulk_runs WHERE method=? AND batch_key=?",method,batch);
            if(!existing.isEmpty()) {
                var row=existing.getFirst();
                if("SUCCESS".equals(row.get("status")))return false;
                if(row.get("lease_until")!=null && SqlIds.instant(row.get("lease_until")).isAfter(now))return false;
                if(row.get("next_attempt_at")!=null && SqlIds.instant(row.get("next_attempt_at")).isAfter(now))return false;
                if(((Number)row.get("attempts")).intValue()>=3)return false;
                db.update("UPDATE gdelt_bulk_runs SET status='RUNNING',attempts=attempts+1,lease_until=?,updated_time=CURRENT_TIMESTAMP WHERE id=?",
                    Timestamp.from(now.plusSeconds(180)),row.get("id"));
            } else db.update("""
                INSERT INTO gdelt_bulk_runs(method,batch_key,status,attempts,lease_until,next_attempt_at)
                VALUES (?,?,'RUNNING',1,?,?)
                """,method,batch,Timestamp.from(now.plusSeconds(180)),Timestamp.from(now));
            return true;
        }));
        if(!claimed)return new Run(method,batch,"SKIPPED",0,0,"Lot déjà collecté, en cours ou en attente de reprise.",null,false);
        log.info("news.collect method={} batch={} status=STARTED",method,batch);
        try {
            var found=job.parse();
            int inserted=Objects.requireNonNull(tx.execute(status->{
                int count=0;
                for(var article:found.articles()) {
                    var ids=db.queryForList("SELECT id FROM assets WHERE symbol=?",Long.class,article.symbol());
                    if(ids.isEmpty())continue;
                    long asset=ids.getFirst();
                    Integer exists=db.queryForObject("SELECT COUNT(*) FROM market_news WHERE asset_id=? AND url=?",
                        Integer.class,asset,article.url());
                    if(exists>0)continue;
                    db.update("""
                        INSERT INTO market_news(asset_id,source,title,url,published_time,sentiment_score,sentiment_method,source_country,language,region_tag)
                        VALUES (?,?,?,?,?,NULL,NULL,NULL,NULL,NULL)
                        """,asset,article.source(),article.title(),article.url(),
                        article.publishedAt()==null?null:Timestamp.from(article.publishedAt()));
                    count++;
                }
                db.update("""
                    UPDATE gdelt_bulk_runs SET status='SUCCESS',inserted_count=?,matched_count=?,
                    error_code=NULL,lease_until=NULL,updated_time=CURRENT_TIMESTAMP
                    WHERE method=? AND batch_key=?
                    """,count,found.matches(),method,batch);
                return count;
            }));
            log.info("news.collect method={} batch={} status=SUCCESS matched={} inserted={}",method,batch,found.matches(),inserted);
            return new Run(method,batch,"SUCCESS",found.matches(),inserted,
                "Actualités collectées et dédupliquées.",null,false);
        } catch(Exception error) {
            String code=error instanceof NewsIngestionException typed?typed.code()
                :error instanceof IOException io && "DECOMPRESSED_LIMIT".equals(io.getMessage())?"GDELT_DECOMPRESSED_LIMIT"
                :error instanceof IOException io && "ARCHIVE_LIMIT".equals(io.getMessage())?"GDELT_ARCHIVE_ROW_LIMIT"
                :error instanceof IOException io && "INVALID_GKG_ARCHIVE".equals(io.getMessage())?"GDELT_GKG_ZIP_INVALID"
                :error instanceof IOException?"GDELT_ARCHIVE_IO_FAILURE":"NEWS_ARCHIVE_PARSE_FAILED";
            long delay=error instanceof NewsIngestionException typed?Math.max(60,typed.retryAfterSeconds()):900;
            db.update("""
                UPDATE gdelt_bulk_runs SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'DEFERRED' END,
                error_code=?,lease_until=NULL,next_attempt_at=?,updated_time=CURRENT_TIMESTAMP
                WHERE method=? AND batch_key=?
                """,code,Timestamp.from(clock.instant().plusSeconds(delay)),method,batch);
            log.warn("news.collect method={} batch={} status=DEFERRED reason={} exception={} ioReason={}",
                method,batch,code,error.getClass().getSimpleName(),
                error instanceof IOException io ? io.getMessage() : "N/A");
            if(error instanceof NewsIngestionException typed)throw typed;
            throw new NewsIngestionException(code,HttpStatus.BAD_GATEWAY,true,delay);
        }
    }

    private Map<String,String> watchlist() {
        Map<String,String> names=new LinkedHashMap<>();
        db.queryForList("""
            SELECT a.symbol,a.name FROM assets a JOIN watchlist w ON w.asset_id=a.id
            WHERE w.enabled=TRUE ORDER BY a.symbol LIMIT 150
            """).forEach(row->names.put((String)row.get("symbol"),(String)row.get("name")));
        return names;
    }

    private InputStream fetch(String address) {
        try {
            URI uri=URI.create(address);
            if(!"data.gdeltproject.org".equals(uri.getHost()) && !"127.0.0.1".equals(uri.getHost()))
                throw new IOException("HOST_BLOCKED");
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                .header("User-Agent","BourseAI/0.3 research archive reader")
                .GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200) {
                response.body().close();
                if(response.statusCode()==429)throw new NewsIngestionException("GDELT_RATE_LIMIT",HttpStatus.TOO_MANY_REQUESTS,true,3600);
                if(response.statusCode()==404)throw new NewsIngestionException("GDELT_BATCH_NOT_PUBLISHED",HttpStatus.SERVICE_UNAVAILABLE,true,900);
                throw new NewsIngestionException("GDELT_UPSTREAM_UNAVAILABLE",HttpStatus.BAD_GATEWAY,true,900);
            }
            // Limit compressed bytes; each parser additionally bounds decompressed rows and line length.
            return new BoundedStream(response.body(),30_000_000);
        }catch(NewsIngestionException error){throw error;}
        catch(Exception e){throw new NewsIngestionException("GDELT_NETWORK_FAILURE",HttpStatus.BAD_GATEWAY,true,900);}
    }

    private String readSmallText(String address,int max) {
        try(var input=fetch(address)) {
            byte[] bytes=input.readNBytes(max+1);
            if(bytes.length>max)throw new IOException("INDEX_TOO_LARGE");
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(NewsIngestionException e){throw e;}
        catch(Exception e){throw new NewsIngestionException("GDELT_INDEX_INVALID",HttpStatus.BAD_GATEWAY,true,900);}
    }
    private static final class BoundedStream extends FilterInputStream {
        private long remaining;
        BoundedStream(InputStream inner,long max){super(inner);remaining=max;}
        @Override public int read() throws IOException {
            if(remaining<=0)throw new IOException("COMPRESSED_LIMIT");
            int value=super.read();
            if(value!=-1)remaining--;return value;
        }
        @Override public int read(byte[] bytes,int offset,int count)throws IOException {
            if(remaining<=0)throw new IOException("COMPRESSED_LIMIT");
            int n=super.read(bytes,offset,(int)Math.min(count,remaining));
            if(n>0)remaining-=n;return n;
        }
    }
    @Scheduled(initialDelayString="${news.bulk.initial-delay-ms:300000}",
        fixedDelayString="${news.bulk.delay-ms:3600000}")
    public void scheduled() {
        if(!scheduled)return;
        // GKG: latest actually PUBLISHED batch from the GKG manifest.
        // Never infer previous archive URLs from a sequence of clock slots.
        runScheduled("GKG",null);
        // NGrams: a separate minute-based feed; one conservative probe per hour.
        // A 404 means that particular minute is not available; do not sweep
        // imaginary GKG-aligned timestamps or consume thousands of HTTP calls.
        runScheduled("NGRAMS",null);
        // RSS feeds have separate per-source min intervals and leases. Process up to 10
        // distinct due sources each hour; never stop other feeds after one failure.
        for(int i=0;i<10;i++) {
            try {
                Run result=run("RSS",null);
                if(result.status().equals("IDLE"))break;
                log.debug("news.schedule method=RSS status={} source={}",result.status(),result.batch());
            } catch(NewsIngestionException e) {
                log.warn("news.schedule method=RSS reason={}",e.code());
            } catch(Exception e) {
                log.error("news.schedule method=RSS failureType={}",e.getClass().getSimpleName());
            }
        }
    }
    private void runScheduled(String method,String slot) {
        try {
            Run result=run(method,slot);
            log.debug("news.schedule method={} batch={} status={} inserted={}",method,slot,result.status(),result.inserted());
        } catch(NewsIngestionException e) {
            log.warn("news.schedule method={} batch={} reason={} retryable={}",method,slot,e.code(),e.retryable());
        } catch(Exception e) {
            log.error("news.schedule method={} batch={} failureType={}",method,slot,e.getClass().getSimpleName());
        }
    }
}
