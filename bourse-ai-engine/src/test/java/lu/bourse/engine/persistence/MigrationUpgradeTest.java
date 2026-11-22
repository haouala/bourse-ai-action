package lu.bourse.engine.persistence;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Upgrade a populated V7 schema through V18 in isolation; never alters the application's schema. */
class MigrationUpgradeTest {
    @Test void v8PreservesExistingMarksAndBackfillsPolicyAndFxTimestamp()throws Exception{
        String configured=System.getenv("BOURSE_TEST_DB_URL"),url=configured==null||configured.isBlank()?"jdbc:h2:mem:upgrade_"+UUID.randomUUID()+";MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1":configured;
        String user=configured==null||configured.isBlank()?"sa":System.getenv("BOURSE_TEST_DB_USER"),password=configured==null||configured.isBlank()?"":System.getenv("BOURSE_TEST_DB_PASSWORD");
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(Connection connection=DriverManager.getConnection(url,user,password)){
            connection.createStatement().execute("CREATE SCHEMA \""+schema+"\"");
            try{
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("7").load().migrate();
                connection.setSchema(schema);var sql=connection.createStatement();
                sql.executeUpdate("INSERT INTO assets(id,symbol,name,asset_type,currency) VALUES (1,'LEGACY','Legacy','STOCK','USD')");
                sql.executeUpdate("INSERT INTO watchlist(asset_id,provider_symbol,region,market_zone,session_open,session_close) VALUES (1,'LEGACY','US','America/New_York','09:30','16:00')");
                sql.executeUpdate("INSERT INTO portfolios(id,name,base_currency,initial_capital,max_loss_amount) VALUES (1,'Legacy','EUR',1000,100)");
                sql.executeUpdate("INSERT INTO positions(id,portfolio_id,asset_id,quantity,average_entry_quote,remaining_cost_base,horizon,signal_timeframe,opened_at,status) VALUES (1,1,1,1,100,90,'LONG','1d',TIMESTAMP WITH TIME ZONE '2026-11-19 14:00:00+00','OPEN')");
                sql.executeUpdate("INSERT INTO position_marks(position_id,price_quote,fx_to_base,observed_at,source) VALUES (1,95,0.9,TIMESTAMP WITH TIME ZONE '2026-11-19 15:00:00+00','LEGACY')");
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                try(var rows=sql.executeQuery("SELECT price_quote,observed_at,fx_observed_at FROM position_marks WHERE position_id=1")){assertThat(rows.next()).isTrue();assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("95");assertThat(rows.getTimestamp(3)).isEqualTo(rows.getTimestamp(2));}
                try(var rows=sql.executeQuery("SELECT calendar_code FROM asset_market_policies WHERE asset_id=1")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("US_EQUITIES");}
                assertThat(Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().info().current().getVersion().toString()).isEqualTo("18");
            }finally{connection.setSchema("PUBLIC".equals(connection.getMetaData().getDatabaseProductName())?"PUBLIC":connection.getMetaData().getDatabaseProductName().equals("H2")?"PUBLIC":"public");connection.createStatement().execute("DROP SCHEMA \""+schema+"\" CASCADE");}
        }
    }
}
