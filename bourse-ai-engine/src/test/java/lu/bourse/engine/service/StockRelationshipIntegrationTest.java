package lu.bourse.engine.service;

import lu.bourse.engine.provider.DatabaseIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class StockRelationshipIntegrationTest extends DatabaseIntegrationSupport {
 @Autowired JdbcTemplate jdbc;
 @Autowired StockRelationshipService service;
 @Test void graphTraversesDirectionalEdgesAndRetainsEvidenceWithoutCycles(){
  for(String ticker:new String[]{"RELN","RELT","RELA"})
   jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES (?,?,?,?)",ticker,"Fixture "+ticker,"STOCK","USD");
  try{
   LocalDate today=LocalDate.now().minusDays(1);
   var first=service.add(new StockRelationshipService.Submission("RELN","RELT","SUPPLIES_TO","HIGH",
    "https://example.org/filing-1","Documented components agreement",today,null,null));
   var second=service.add(new StockRelationshipService.Submission("RELT","RELA","COMPETES_WITH","MEDIUM",
    "https://example.org/filing-2","Reported business competition",today,null,null));
   assertThat(first.direction()).isEqualTo("DIRECTED");
   assertThat(second.direction()).isEqualTo("BIDIRECTIONAL");
   assertThat(service.graph("RELN",1).nodes()).hasSize(2);
   var deep=service.graph("RELN",2);
   assertThat(deep.nodes()).hasSize(3);
   assertThat(deep.edges()).hasSize(2);
   assertThat(deep.edges()).anyMatch(x->x.relationshipType().equals("SUPPLIES_TO")
     &&x.fromSymbol().equals("RELN")&&x.toSymbol().equals("RELT"));
   assertThatThrownBy(()->service.add(new StockRelationshipService.Submission("RELA","RELT","COMPETES_WITH","MEDIUM",
    "https://example.org/filing-2","Duplicated evidence",today,null,null)))
    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already exists");
   assertThat(service.retract(first.id()).status()).isEqualTo("RETRACTED");
   assertThat(service.direct("RELN",false)).isEmpty();
   assertThat(service.direct("RELN",true)).hasSize(1);
   assertThat(service.graph("RELN",2).nodes()).hasSize(1);
  } finally {
   jdbc.update("DELETE FROM company_relationships WHERE from_asset_id IN (SELECT id FROM assets WHERE symbol LIKE 'REL%') OR to_asset_id IN (SELECT id FROM assets WHERE symbol LIKE 'REL%')");
   jdbc.update("DELETE FROM assets WHERE symbol IN ('RELN','RELT','RELA')");
  }
 }

 @Test void rejectsMissingEvidenceSelfLoopAndInvalidDepth(){
  assertThatThrownBy(()->service.add(new StockRelationshipService.Submission("RELN","RELN","PARTNER_OF",
   "HIGH","https://example.org/e","Self",LocalDate.now(),null,null)))
   .isInstanceOf(ResponseStatusException.class);
  assertThatThrownBy(()->service.add(new StockRelationshipService.Submission("RELN","RELT","PARTNER_OF",
   "HIGH","http://example.org/e","Insecure evidence",LocalDate.now(),null,null)))
   .isInstanceOf(ResponseStatusException.class);
  assertThatThrownBy(()->service.graph("RELN",3))
   .isInstanceOf(ResponseStatusException.class);
 }
}
