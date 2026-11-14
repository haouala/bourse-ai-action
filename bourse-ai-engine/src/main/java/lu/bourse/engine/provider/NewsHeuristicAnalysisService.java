package lu.bourse.engine.provider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class NewsHeuristicAnalysisService {
 private static final List<String> POSITIVE=List.of("beats estimates","raises guidance","record revenue","profit rises","upgrades","contract win","strong earnings");
 private static final List<String> NEGATIVE=List.of("misses estimates","cuts guidance","lawsuit","investigation","profit falls","downgrades","recall","bankruptcy");
 private final JdbcTemplate jdbc;
 public record Result(String symbol,int analyzed,int positive,int neutral,int negative,String method,String warning){}
 public NewsHeuristicAnalysisService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Result analyze(String raw){
  if(raw==null||!raw.matches("[A-Za-z0-9._-]{1,40}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol");
  String symbol=raw.toUpperCase(Locale.ROOT);
  var rows=jdbc.query("SELECT n.id,n.title FROM market_news n JOIN assets a ON a.id=n.asset_id WHERE a.symbol=?",(r,i)->new Object[]{r.getLong(1),r.getString(2)},symbol);
  int positive=0,negative=0,neutral=0;
  for(Object[] row:rows){String title=((String)row[1]).toLowerCase(Locale.ROOT);
   int p=(int)POSITIVE.stream().filter(title::contains).count(),n=(int)NEGATIVE.stream().filter(title::contains).count();
   int sentiment=Integer.compare(p,n);if(sentiment>0)positive++;else if(sentiment<0)negative++;else neutral++;
   jdbc.update("UPDATE market_news SET sentiment_score=?,sentiment_method='KEYWORDS_V1',updated_time=CURRENT_TIMESTAMP WHERE id=?",sentiment, row[0]);
  }
  return new Result(symbol,rows.size(),positive,neutral,negative,"KEYWORDS_V1","Simple English headline keywords, NOT an AI interpretation or a return forecast");
 }
}
