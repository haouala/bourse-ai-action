package lu.bourse.engine.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="market.sec.user-agent=BourseAI Engineering engineering@sample.test")
class InsiderFilingIntegrationTest extends DatabaseIntegrationSupport {
 private static final AtomicReference<String> XML=new AtomicReference<>(fixtureXml());
 private static final HttpServer server=startServer();
 @Autowired InsiderFilingService service;
 @Autowired JdbcTemplate jdbc;

 private static HttpServer startServer(){
  try{
   var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   s.createContext("/submissions/",exchange->send(exchange,"{\"cik\":12345,\"filings\":{\"recent\":{\"form\":[\"4\"],\"accessionNumber\":[\"0000123456-26-000001\"],\"primaryDocument\":[\"ownership.xml\"],\"filingDate\":[\"2026-11-17\"]}}}"));
   s.createContext("/Archives/",exchange->send(exchange,XML.get()));
   s.start();return s;
  }catch(IOException e){throw new IllegalStateException(e);}
 }
 private static void send(com.sun.net.httpserver.HttpExchange exchange,String content)throws IOException{
  byte[] data=content.getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().add("Content-Type","application/xml");
  exchange.sendResponseHeaders(200,data.length);
  try(var stream=exchange.getResponseBody()){stream.write(data);}
 }
 @DynamicPropertySource
 static void endpoint(DynamicPropertyRegistry registry){
  String base="http://127.0.0.1:"+server.getAddress().getPort();
  registry.add("market.sec.base-url",()->base);
  registry.add("market.sec.archives-base-url",()->base);
 }
 private static String fixtureXml(){
  return """
   <?xml version="1.0" encoding="UTF-8"?>
   <ownershipDocument>
    <issuer><issuerCik>0000012345</issuerCik></issuer>
    <reportingOwner><reportingOwnerId><rptOwnerCik>0000088888</rptOwnerCik><rptOwnerName>Sample Director</rptOwnerName></reportingOwnerId>
     <reportingOwnerRelationship><isDirector>1</isDirector><isOfficer>0</isOfficer></reportingOwnerRelationship></reportingOwner>
    <nonDerivativeTable>
     <nonDerivativeTransaction>
      <securityTitle><value>Common Stock</value></securityTitle>
      <transactionDate><value>2026-11-15</value></transactionDate>
      <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
      <transactionAmounts><transactionShares><value>1000</value></transactionShares>
       <transactionPricePerShare><value>140</value></transactionPricePerShare>
       <transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode></transactionAmounts>
      <postTransactionAmounts><sharesOwnedFollowingTransaction><value>9000</value></sharesOwnedFollowingTransaction></postTransactionAmounts>
      <ownershipNature><directOrIndirectOwnership><value>D</value></directOrIndirectOwnership></ownershipNature>
     </nonDerivativeTransaction>
    </nonDerivativeTable>
   </ownershipDocument>
   """;
 }
 @BeforeEach void prepare(){
  jdbc.update("INSERT INTO assets(symbol,name,asset_type,currency) VALUES ('TESTINSIDER','Test issuer','STOCK','USD')");
  XML.set(fixtureXml());
 }
 @AfterEach void cleanup(){
  jdbc.update("DELETE FROM insider_transactions WHERE asset_id=(SELECT id FROM assets WHERE symbol='TESTINSIDER')");
  jdbc.update("DELETE FROM assets WHERE symbol='TESTINSIDER'");
 }
 @AfterAll static void stop(){server.stop(0);}
 @Test void importsOfficialForm4WithoutDuplicatesAndRaisesInformationalAlert(){
  var first=service.sync("TESTINSIDER",12345,5);
  assertThat(first.transactionsInserted()).isEqualTo(1);
  assertThat(first.skippedFilings()).isZero();
  assertThat(service.sync("TESTINSIDER",12345,5).transactionsInserted()).isZero();
  var history=service.history("TESTINSIDER",10);
  assertThat(history).singleElement().satisfies(t->{
   assertThat(t.owner()).isEqualTo("Sample Director");
   assertThat(t.shares()).isEqualByComparingTo("1000");
   assertThat(t.direction()).isEqualTo("D");
   assertThat(t.url()).contains("/Archives/edgar/data/12345/");
  });
  assertThat(service.alerts("TESTINSIDER",365)).singleElement()
   .satisfies(a->assertThat(a.severity()).isEqualTo("INFORMATIONAL_SALE"));
 }
 @Test void malformedOrUnsafeXmlDoesNotInsertTransactions(){
  XML.set("<!DOCTYPE a [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><ownershipDocument>&xxe;</ownershipDocument>");
  assertThat(service.sync("TESTINSIDER",12345,5).transactionsInserted()).isZero();
  assertThat(service.history("TESTINSIDER",10)).isEmpty();
 }
 @Test void invalidInputsRejected(){
  assertThatThrownBy(()->service.sync("TESTINSIDER",12345,31)).hasMessageContaining("Invalid");
  assertThatThrownBy(()->service.history("TESTINSIDER",201)).hasMessageContaining("Invalid");
 }
}
