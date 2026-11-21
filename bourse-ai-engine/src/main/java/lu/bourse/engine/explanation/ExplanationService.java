package lu.bourse.engine.explanation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lu.bourse.engine.calendar.MarketCalendarService;
import lu.bourse.engine.persistence.SqlIds;
import lu.bourse.engine.seasonality.SeasonalityService;
import lu.bourse.engine.service.FinancialIndicators;
import lu.bourse.engine.service.Timeframe;
import lu.bourse.engine.social.SocialSignalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Versioned heuristic contributions, not gain probabilities or executable orders. */
@Service
public class ExplanationService {
    public static final String METHOD="EXPLAINABLE_RULES_V1_EXPERIMENTAL";
    public enum Horizon { DAY,MONTH,YEAR }
    public record Rules(int technicalMax,int fundamentalMax,int informationMax,BigDecimal marginChangeThreshold,BigDecimal rsiOversold,BigDecimal rsiOverbought){}
    public record NewRules(String name,Rules rules){}
    public record Factor(String key,String category,BigDecimal points,String status,String explanation,Object evidence){}
    public record Analysis(long id,String symbol,Horizon horizon,long rulesetId,String method,Instant asOf,
        BigDecimal score,int coverageScore,String status,MarketCalendarService.Quality quoteQuality,List<Factor> factors,
        Long previousSnapshotId,BigDecimal previousScore,BigDecimal scoreChange,Map<String,BigDecimal> contributionChanges,
        boolean rulesetChanged,List<String> limitations){}
    private final JdbcTemplate db;private final Clock clock;private final ObjectMapper json;private final org.springframework.transaction.support.TransactionTemplate bootstrap;
    private final MarketCalendarService calendars;private final SeasonalityService seasonality;private final SocialSignalService social;
    private final lu.bourse.engine.information.InformationAnalysisService informationAnalyses;
    public ExplanationService(JdbcTemplate db,Clock clock,ObjectMapper json,MarketCalendarService calendars,SeasonalityService seasonality,SocialSignalService social,org.springframework.transaction.PlatformTransactionManager manager,lu.bourse.engine.information.InformationAnalysisService informationAnalyses){this.db=db;this.clock=clock;this.json=json;this.calendars=calendars;this.seasonality=seasonality;this.social=social;this.bootstrap=new org.springframework.transaction.support.TransactionTemplate(manager);this.informationAnalyses=informationAnalyses;}
    @PostConstruct void seed(){bootstrap.executeWithoutResult(status->{db.queryForObject("SELECT id FROM explanation_guard WHERE id=1 FOR UPDATE",Integer.class);seedUnlocked();});}
    private void seedUnlocked(){
        for(Horizon h:Horizon.values())if(db.queryForObject("SELECT COUNT(*) FROM explanation_rulesets WHERE name=?",Integer.class,"DEFAULT_"+h+"_V1")==0){
            Rules r=switch(h){case DAY->new Rules(20,5,20,new BigDecimal("3"),new BigDecimal("30"),new BigDecimal("70"));case MONTH->new Rules(15,15,20,new BigDecimal("3"),new BigDecimal("30"),new BigDecimal("70"));case YEAR->new Rules(10,25,15,new BigDecimal("3"),new BigDecimal("30"),new BigDecimal("70"));};
            db.update("INSERT INTO explanation_rulesets(name,method,configuration_json,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)","DEFAULT_"+h+"_V1",METHOD,write(r));
        }
    }
    @Transactional public long createRules(NewRules r){
        db.queryForObject("SELECT id FROM explanation_guard WHERE id=1 FOR UPDATE",Integer.class);
        if(r==null||r.name()==null||!r.name().matches("[A-Za-z0-9._-]{1,120}")||r.name().startsWith("DEFAULT_"))bad("A new non-default ruleset name is required");validate(r.rules());
        if(db.queryForObject("SELECT COUNT(*) FROM explanation_rulesets WHERE name=?",Integer.class,r.name())>0)throw new ResponseStatusException(CONFLICT,"Ruleset name exists; create a new version");
        return SqlIds.insert(db,"INSERT INTO explanation_rulesets(name,method,configuration_json,created_at) VALUES (?,?,?,?)",r.name(),METHOD,write(r.rules()),ts(clock.instant()));
    }
    public List<Map<String,Object>> rules(){return db.queryForList("SELECT * FROM explanation_rulesets ORDER BY id");}
    @Transactional public Analysis calculate(String symbol,Horizon horizon,Long requestedRules){
        if(horizon==null)bad("Horizon required");long asset=calendars.assetId(symbol);Instant now=clock.instant();
        db.queryForObject("SELECT id FROM explanation_guard WHERE id=1 FOR UPDATE",Integer.class);
        long rulesId=requestedRules==null?db.queryForObject("SELECT id FROM explanation_rulesets WHERE name=?",Long.class,"DEFAULT_"+horizon+"_V1"):requestedRules;
        var stored=db.queryForList("SELECT * FROM explanation_rulesets WHERE id=?",rulesId);if(stored.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown ruleset");
        Rules config=read((String)stored.getFirst().get("configuration_json"),Rules.class);validate(config);
        var factors=new ArrayList<Factor>();technical(asset,switch(horizon){case DAY->"15m";case MONTH->"1h";case YEAR->"1d";},config,now,factors);
        boolean crypto="CRYPTO".equals(db.queryForObject("SELECT asset_type FROM assets WHERE id=?",String.class,asset));
        fundamental(asset,config,now,crypto,factors);information(asset,config,horizon,factors);
        factors.add(new Factor("SEASONALITY","SEASONALITY",BigDecimal.ZERO,"EXPLORATORY_ONLY","Statistiques mensuelles, trimestrielles et annuelles ; contribution nulle avant validation indépendante.",seasonality.analyze(symbol,now)));
        var quality=quoteQuality(asset,now);int expected=(config.technicalMax()>0?2:0)+(!crypto&&config.fundamentalMax()>0?1:0)+(config.informationMax()>0?1:0);
        int available=(int)factors.stream().filter(f->f.category().equals("TECHNICAL")&&f.status().equals("AVAILABLE")).count();
        if(factors.stream().anyMatch(f->f.category().equals("FUNDAMENTAL")&&f.status().equals("AVAILABLE")))available++;
        if(factors.stream().anyMatch(f->f.category().equals("INFORMATION")&&f.status().equals("AVAILABLE")))available++;
        int coverage=expected==0?0:Math.min(100,available*100/expected);
        BigDecimal score=available==0?null:round(new BigDecimal("50").add(factors.stream().map(Factor::points).reduce(BigDecimal.ZERO,BigDecimal::add)));
        String status=!quality.usable()?"REVIEW_DATA_UNRELIABLE":(coverage<100||factors.stream().anyMatch(f->Set.of("MISSING","STALE","UNRESOLVED").contains(f.status())))?"REVIEW_WITH_MISSING_DATA":"REVIEW_ONLY_UNVALIDATED";
        var previous=db.queryForList("SELECT * FROM explanation_snapshots WHERE asset_id=? AND horizon=? ORDER BY id DESC LIMIT 1",asset,horizon.name());
        Long previousId=null;BigDecimal previousScore=null,change=null;boolean changedRules=false;var changes=new LinkedHashMap<String,BigDecimal>();
        if(!previous.isEmpty()){
            var old=previous.getFirst();previousId=((Number)old.get("id")).longValue();previousScore=(BigDecimal)old.get("score");changedRules=((Number)old.get("ruleset_id")).longValue()!=rulesId;
            changes.putAll(differences(previousId,factors));if(score!=null&&previousScore!=null)change=round(score.subtract(previousScore));
        }
        var limits=List.of("Score heuristique expérimental ; aucune probabilité de gain ni ordre automatique","CoverageScore mesure les composantes disponibles, pas une confiance de prédiction","Les explications attribuent les points du modèle ; elles ne prouvent pas la cause d'un mouvement du cours","Cours ajustés, qualité des imports, frais et tests indépendants restent à vérifier","Classification des événements et attribution des paroles validées manuellement ; aucun LLM connecté");
        long id=SqlIds.insert(db,"INSERT INTO explanation_snapshots(asset_id,ruleset_id,horizon,as_of,computed_at,score,coverage_score,status,result_json) VALUES (?,?,?,?,?,?,?,?,?)",asset,rulesId,horizon.name(),ts(now),ts(now),score,coverage,status,"{}");
        var result=new Analysis(id,symbol.toUpperCase(Locale.ROOT),horizon,rulesId,METHOD,now,score,coverage,status,quality,List.copyOf(factors),previousId,previousScore,change,changes,changedRules,limits);
        db.update("UPDATE explanation_snapshots SET result_json=? WHERE id=?",write(result),id);
        for(Factor f:factors)db.update("INSERT INTO explanation_factors(snapshot_id,factor_key,category,points,explanation,evidence_json) VALUES (?,?,?,?,?,?)",id,f.key(),f.category(),round(f.points()),f.explanation(),write(f.evidence()));
        return result;
    }
    private void technical(long asset,String tf,Rules rules,Instant now,List<Factor> factors){
        if(rules.technicalMax()==0){for(String key:List.of("MACD","RSI"))factors.add(new Factor("TECH_"+key,"TECHNICAL",BigDecimal.ZERO,"DISABLED","Règle technique désactivée dans cette version.",Map.of()));return;}
        var period=Timeframe.parse(tf);var rows=db.queryForList("SELECT id,open_time,close_price,provider FROM market_candles WHERE asset_id=? AND timeframe=? AND open_time<=? ORDER BY open_time DESC LIMIT 250",asset,tf,ts(now));
        var bars=rows.stream().filter(r->!calendars.barClose(asset,SqlIds.instant(r.get("open_time")),period.duration()).isAfter(now)).toList();
        if(bars.size()<35){for(String k:List.of("MACD","RSI"))factors.add(missing("TECH_"+k,"TECHNICAL","Au moins 35 bougies closes requises sur "+tf));return;}
        var prices=new ArrayList<BigDecimal>();for(int i=bars.size()-1;i>=0;i--)prices.add((BigDecimal)bars.get(i).get("close_price"));
        var indicator=FinancialIndicators.calculate(prices);BigDecimal max=BigDecimal.valueOf(rules.technicalMax()).divide(new BigDecimal("2"));
        var data=new LinkedHashMap<String,Object>();data.put("timeframe",tf);data.put("indicators",indicator);data.put("candles",bars.stream().map(ExplanationService::lower).toList());
        var state=calendars.state(asset,period.duration(),now);Instant last=calendars.barClose(asset,SqlIds.instant(bars.getFirst().get("open_time")),period.duration());
        boolean fresh=state.expectedClose()!=null&&!last.isBefore(state.expectedClose());String status=fresh?"AVAILABLE":"STALE";
        factors.add(new Factor("TECH_MACD","TECHNICAL",fresh?max.multiply(BigDecimal.valueOf(indicator.macdHistogram().signum())):BigDecimal.ZERO,status,"MACD = EMA12 − EMA26 ; signal = EMA9 du MACD. Histogramme positif : contribution favorable selon cette règle ; négatif : défavorable. Série "+tf+".",data));
        int sign=indicator.rsi14().compareTo(rules.rsiOversold())<0?1:indicator.rsi14().compareTo(rules.rsiOverbought())>0?-1:0;
        factors.add(new Factor("TECH_RSI","TECHNICAL",fresh?max.multiply(BigDecimal.valueOf(sign)):BigDecimal.ZERO,status,"RSI14 = "+indicator.rsi14()+" ; seuils "+rules.rsiOversold()+" / "+rules.rsiOverbought()+". Un extrême bas est favorable à examiner selon la règle, sans garantir un rebond.",data));
    }
    private void fundamental(long asset,Rules rules,Instant now,boolean crypto,List<Factor> factors){
        if(rules.fundamentalMax()==0){factors.add(new Factor("NET_MARGIN_CHANGE","FUNDAMENTAL",BigDecimal.ZERO,"DISABLED","Règle financière désactivée.",Map.of()));return;}
        if(crypto){factors.add(new Factor("NET_MARGIN_CHANGE","FUNDAMENTAL",BigDecimal.ZERO,"NOT_APPLICABLE","Marge d'entreprise non applicable à cette crypto.",Map.of()));return;}
        var rows=db.queryForList("SELECT * FROM financial_facts WHERE asset_id=? AND filed_date<? AND form_type='10-K' AND period_start IS NOT NULL AND concept IN ('Revenues','RevenueFromContractWithCustomerExcludingAssessedTax','NetIncomeLoss') ORDER BY period_end DESC,filed_date DESC,id DESC",asset,Date.valueOf(now.atZone(ZoneOffset.UTC).toLocalDate()));
        var periods=new LinkedHashMap<String,Map<String,Map<String,Object>>>();
        for(var r:rows){LocalDate start=((Date)r.get("period_start")).toLocalDate(),end=((Date)r.get("period_end")).toLocalDate();long duration=java.time.temporal.ChronoUnit.DAYS.between(start,end);if(duration<300||duration>380||end.isAfter(now.atZone(ZoneOffset.UTC).toLocalDate()))continue;periods.computeIfAbsent(start+"/"+end+"/"+r.get("unit"),x->new HashMap<>()).putIfAbsent((String)r.get("concept"),r);}
        var complete=new ArrayList<Map<String,Object>>();
        for(var values:periods.values()){
            var revenue=values.getOrDefault("RevenueFromContractWithCustomerExcludingAssessedTax",values.get("Revenues"));var income=values.get("NetIncomeLoss");
            if(revenue==null||income==null||((BigDecimal)revenue.get("value")).signum()<=0||!revenue.get("accession_number").equals(income.get("accession_number"))||!((String)revenue.get("unit")).matches("[A-Z]{3}"))continue;
            var margin=((BigDecimal)income.get("value")).multiply(new BigDecimal("100")).divide((BigDecimal)revenue.get("value"),8,RoundingMode.HALF_UP);
            complete.add(Map.of("marginPercent",margin,"revenueFact",lower(revenue),"incomeFact",lower(income),"unit",revenue.get("unit"),"end",revenue.get("period_end")));
        }
        if(complete.size()<2){factors.add(missing("NET_MARGIN_CHANGE","FUNDAMENTAL","Deux exercices annuels comparables de chiffre d'affaires et résultat net sont requis."));return;}
        var latest=complete.getFirst();Map<String,Object> prior=null;LocalDate end=((Date)latest.get("end")).toLocalDate();
        for(int i=1;i<complete.size();i++){var candidate=complete.get(i);long gap=java.time.temporal.ChronoUnit.DAYS.between(((Date)candidate.get("end")).toLocalDate(),end);if(candidate.get("unit").equals(latest.get("unit"))&&gap>=300&&gap<=430){prior=candidate;break;}}
        if(prior==null){factors.add(missing("NET_MARGIN_CHANGE","FUNDAMENTAL","Aucun exercice précédent comparable dans la même unité."));return;}
        BigDecimal change=((BigDecimal)latest.get("marginPercent")).subtract((BigDecimal)prior.get("marginPercent"));int sign=change.abs().compareTo(rules.marginChangeThreshold())>=0?change.signum():0;
        boolean stale=end.plusMonths(18).isBefore(now.atZone(ZoneOffset.UTC).toLocalDate());
        factors.add(new Factor("NET_MARGIN_CHANGE","FUNDAMENTAL",stale?BigDecimal.ZERO:BigDecimal.valueOf((long)sign*rules.fundamentalMax()),stale?"STALE":"AVAILABLE","Marge nette = résultat net / chiffre d'affaires × 100. Variation annuelle : "+change+" points ; seuil de contribution : "+rules.marginChangeThreshold()+" points.",Map.of("latest",latest,"prior",prior,"changePercentagePoints",change,"availabilityPolicy","FILED_DATE_PLUS_ONE_DAY")));
    }
    private void information(long asset,Rules rules,Horizon horizon,List<Factor> factors){
        if(rules.informationMax()==0){factors.add(new Factor("INFO_DISABLED","INFORMATION",BigDecimal.ZERO,"DISABLED","Composante informationnelle désactivée.",Map.of()));return;}
        var events=db.queryForList("SELECT event_id FROM social_asset_assessments WHERE asset_id=? ORDER BY event_id",Long.class,asset);
        BigDecimal max=BigDecimal.valueOf(rules.informationMax());
        String symbol=db.queryForObject("SELECT symbol FROM assets WHERE id=?",String.class,asset);
        var ai=informationAnalyses.asset(symbol,horizon.name());
        boolean aiEvidence=!((List<?>)ai.get("proposedEvidence")).isEmpty();
        if(events.isEmpty()){
            if(aiEvidence){boolean available=((Number)ai.get("approvedEventCount")).intValue()>0;boolean conflict=((Number)ai.get("conflictingEventCount")).intValue()>0;
                BigDecimal points=available?max.multiply((BigDecimal)ai.get("approvedScore")).divide(new BigDecimal("100"),4,RoundingMode.HALF_UP):BigDecimal.ZERO;
                factors.add(new Factor("AI_INFORMATION","INFORMATION",points,available&&!conflict?"AVAILABLE":"UNRESOLVED","Propositions IA et résumés consultables avec leurs citations. Seules les interprétations approuvées contribuent ; un événement répété ne multiplie pas ses points. L'attention médiatique reste descriptive.",ai));
            }else factors.add(missing("INFO_NONE","INFORMATION","Aucun événement classifié pour cet actif. Les titres non analysés ne deviennent pas un signal neutre."));return;
        }
        if(aiEvidence)factors.add(new Factor("AI_INFORMATION_SEPARATE","INFORMATION",BigDecimal.ZERO,"SEPARATE_REVIEW","Des événements sociaux existent déjà. Les propositions IA restent séparées pour éviter un double comptage entre les deux chaînes.",ai));
        var rawFactors=new ArrayList<Factor>();BigDecimal total=BigDecimal.ZERO;
        for(long event:events){
            var score=db.queryForMap("SELECT s.*,a.horizon FROM social_impact_scores s JOIN social_asset_assessments a ON a.id=s.assessment_id WHERE a.event_id=? AND a.asset_id=? ORDER BY s.id DESC LIMIT 1",event,asset);
            long halfLife=switch((String)score.get("horizon")){case "INTRADAY"->900;case "DAYS"->86400;case "MONTHS"->2592000;default->31536000;};
            double age=Math.max(0,Duration.between(SqlIds.instant(score.get("scored_at")),clock.instant()).toMillis()/1000.0);
            BigDecimal decay=BigDecimal.valueOf(Math.pow(0.5,age/halfLife));score.put("additional_decay_since_score",decay);
            BigDecimal signed=(BigDecimal)score.get("signed_score"),raw=signed==null?BigDecimal.ZERO:max.multiply(signed).multiply(decay).divide(new BigDecimal("100"),12,RoundingMode.HALF_UP);
            total=total.add(raw);var eventData=db.queryForMap("SELECT * FROM social_events WHERE id=?",event);
            rawFactors.add(new Factor("EVENT_"+event,"INFORMATION",raw,signed==null?"UNRESOLVED":"AVAILABLE","Événement : "+eventData.get("title")+". Exposition, sens, confiance et ancienneté sont détaillés dans les preuves. Le plafond informationnel est appliqué proportionnellement au total, indépendamment de l'ordre des événements.",Map.of("event",lower(eventData),"score",lower(score),"classification","MANUALLY_VALIDATED_INPUTS")));
        }
        BigDecimal scale=total.abs().compareTo(max)>0?max.divide(total.abs(),16,RoundingMode.HALF_UP):BigDecimal.ONE;
        BigDecimal target=round(total.max(max.negate()).min(max)),allocated=BigDecimal.ZERO;
        int lastDirectional=-1;for(int i=0;i<rawFactors.size();i++)if(rawFactors.get(i).points().signum()!=0)lastDirectional=i;
        for(int i=0;i<rawFactors.size();i++){var f=rawFactors.get(i);BigDecimal points=i==lastDirectional?target.subtract(allocated):round(f.points().multiply(scale));allocated=allocated.add(points);factors.add(new Factor(f.key(),f.category(),points,f.status(),f.explanation(),f.evidence()));}
    }
    private MarketCalendarService.Quality quoteQuality(long asset,Instant now){var rows=db.queryForList("SELECT open_time FROM market_candles WHERE asset_id=? AND timeframe='15m' AND open_time<=? ORDER BY open_time DESC LIMIT 1",asset,ts(now.minusSeconds(900)));return calendars.quality(asset,rows.isEmpty()?null:SqlIds.instant(rows.getFirst().get("open_time")).plusSeconds(900),null,false,now);}
    public Map<String,Object> get(long id){var rows=db.queryForList("SELECT result_json FROM explanation_snapshots WHERE id=?",String.class,id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Unknown explanation");try{return json.readValue(rows.getFirst(),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    public List<Map<String,Object>> history(String symbol,Horizon horizon){return db.queryForList("SELECT id,ruleset_id,horizon,as_of,score,coverage_score,status FROM explanation_snapshots WHERE asset_id=? AND horizon=? ORDER BY id DESC LIMIT 100",calendars.assetId(symbol),horizon.name());}
    private Map<String,BigDecimal> differences(long previous,List<Factor> current){var old=new LinkedHashMap<String,BigDecimal>();for(var f:db.queryForList("SELECT factor_key,points FROM explanation_factors WHERE snapshot_id=?",previous))old.put((String)f.get("factor_key"),(BigDecimal)f.get("points"));var result=new LinkedHashMap<String,BigDecimal>();for(Factor f:current){BigDecimal before=old.remove(f.key());result.put(f.key(),round(f.points().subtract(before==null?BigDecimal.ZERO:before)));}old.forEach((k,v)->result.put(k,round(v.negate())));return result;}
    private static Factor missing(String key,String category,String text){return new Factor(key,category,BigDecimal.ZERO,"MISSING",text,Map.of());}
    private static Map<String,Object> lower(Map<String,Object> input){var result=new LinkedHashMap<String,Object>();input.forEach((k,v)->result.put(k.toLowerCase(Locale.ROOT),v));return result;}
    private static BigDecimal round(BigDecimal n){return n.setScale(4,RoundingMode.HALF_UP);}
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
    private static void validate(Rules r){if(r==null||r.technicalMax()<0||r.fundamentalMax()<0||r.informationMax()<0||r.technicalMax()>50||r.fundamentalMax()>50||r.informationMax()>50||(r.technicalMax()+r.fundamentalMax()+r.informationMax()>50||r.technicalMax()+r.fundamentalMax()+r.informationMax()==0)||r.marginChangeThreshold()==null||r.marginChangeThreshold().signum()<=0||r.marginChangeThreshold().compareTo(new BigDecimal("100"))>0||r.rsiOversold()==null||r.rsiOverbought()==null||r.rsiOversold().signum()<0||r.rsiOverbought().compareTo(new BigDecimal("100"))>0||r.rsiOversold().compareTo(r.rsiOverbought())>=0)bad("Invalid bounded rule weights or thresholds");}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("Cannot store explanation audit",e);}}
    private <T>T read(String v,Class<T> t){try{return json.readValue(v,t);}catch(Exception e){throw new IllegalStateException("Invalid stored rules",e);}}
    private static void bad(String text){throw new ResponseStatusException(BAD_REQUEST,text);}
}
