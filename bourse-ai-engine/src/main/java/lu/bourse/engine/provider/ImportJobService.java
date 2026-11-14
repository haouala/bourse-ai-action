package lu.bourse.engine.provider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
@Service
public class ImportJobService {
 private final JdbcTemplate jdbc;
 public ImportJobService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public long start(String symbol,String provider,String dataset){
  org.springframework.jdbc.support.GeneratedKeyHolder keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
  jdbc.update(con->{java.sql.PreparedStatement st=con.prepareStatement("INSERT INTO import_jobs(asset_symbol,provider,dataset,status) VALUES (?,?,?,'RUNNING')",new String[]{"id"});st.setString(1,symbol);st.setString(2,provider);st.setString(3,dataset);return st;},keys);
  return java.util.Objects.requireNonNull(keys.getKey()).longValue();
 }
 public void end(long id,int count,String error){
  jdbc.update("UPDATE import_jobs SET status=?, imported_count=?, error_message=?, finished_time=?, updated_time=CURRENT_TIMESTAMP WHERE id=?",error==null?"SUCCESS":"ERROR",count,error==null?null:error.substring(0,Math.min(900,error.length())),Timestamp.from(Instant.now()),id);
 }
}
