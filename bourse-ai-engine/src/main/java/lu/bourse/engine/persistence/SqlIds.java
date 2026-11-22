package lu.bourse.engine.persistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.*;
import java.time.*;

public final class SqlIds {
    private SqlIds() {}
    public static long insert(JdbcTemplate db,String sql,Object... args) {
        var keys=new GeneratedKeyHolder();
        db.update(connection->{
            var ps=connection.prepareStatement(sql,new String[]{"id"});
            for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);
            return ps;
        },keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
    public static Instant instant(Object x) {
        if(x instanceof Timestamp t)return t.toInstant();
        if(x instanceof OffsetDateTime t)return t.toInstant();
        if(x instanceof Instant t)return t;
        throw new IllegalArgumentException("Unsupported database time");
    }
}
