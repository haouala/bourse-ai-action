package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Sourced business relationships. This is a factual graph, NOT a causal price model.
 * A record's confidence describes evidence quality, not the chance a stock will rise/fall.
 */
@Service
public class StockRelationshipService {
 private static final Set<String> TYPES=Set.of("SUPPLIES_TO","CUSTOMER_OF","COMPETES_WITH",
  "PARTNER_OF","OWNS_STAKE_IN","SECTOR_PEER");
 private static final Set<String> SYMMETRIC=Set.of("COMPETES_WITH","PARTNER_OF","SECTOR_PEER");
 private static final Set<String> CONFIDENCES=Set.of("LOW","MEDIUM","HIGH");
 private final JdbcTemplate jdbc;
 public StockRelationshipService(JdbcTemplate jdbc){this.jdbc=jdbc;}

 public record Submission(String fromSymbol,String toSymbol,String relationshipType,String confidence,
   String evidenceUrl,String evidenceSummary,LocalDate evidenceDate,LocalDate validFrom,LocalDate validUntil){}
 public record Edge(long id,String fromSymbol,String toSymbol,String relationshipType,String direction,
   String confidence,String evidenceUrl,String evidenceSummary,LocalDate evidenceDate,
   LocalDate validFrom,LocalDate validUntil,String status){}
 public record Node(String symbol,String name){}
 public record Graph(String root,int depth,List<Node> nodes,List<Edge> edges,
   String disclaimer){}
 private record Work(String symbol,int depth){}

 private static String symbol(String s){
  if(s==null || !s.matches("[A-Za-z0-9._-]{1,40}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid asset symbol");
  return s.toUpperCase(Locale.ROOT);
 }
 private static String url(String input){
  try{
   if(input==null||input.length()>1500)throw new IllegalArgumentException();
   URI uri=URI.create(input);
   if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null ||
      uri.getHost().isBlank() || uri.getUserInfo()!=null || uri.getFragment()!=null)
    throw new IllegalArgumentException();
   return uri.toASCIIString();
  }catch(Exception ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Evidence must have a valid HTTPS URL");}
 }
 @Transactional
 public Edge add(Submission input){
  if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Relationship required");
  String from=symbol(input.fromSymbol()),to=symbol(input.toSymbol());
  if(from.equals(to))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Self-relations are not allowed");
  if(!TYPES.contains(input.relationshipType()) || !CONFIDENCES.contains(input.confidence()) ||
    input.evidenceSummary()==null || input.evidenceSummary().isBlank() ||
    input.evidenceSummary().length()>1000 || input.evidenceDate()==null ||
    input.evidenceDate().isAfter(LocalDate.now()) ||
    (input.validFrom()!=null&&input.validUntil()!=null&&input.validUntil().isBefore(input.validFrom())))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid relationship metadata");
  String evidence=url(input.evidenceUrl());
  // Symmetric relationships use a canonical direction to avoid opposite duplicates.
  if(SYMMETRIC.contains(input.relationshipType()) && from.compareTo(to)>0){
   String swap=from;from=to;to=swap;
  }
  Long fromId=lookup(from),toId=lookup(to);
  String direction=SYMMETRIC.contains(input.relationshipType())?"BIDIRECTIONAL":"DIRECTED";
  List<Long> existing=jdbc.query("SELECT id FROM company_relationships WHERE from_asset_id=? AND to_asset_id=? AND relationship_type=? AND evidence_url=?",
    (r,i)->r.getLong(1),fromId,toId,input.relationshipType(),evidence);
  if(!existing.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Relationship evidence already exists");
  jdbc.update("INSERT INTO company_relationships(from_asset_id,to_asset_id,relationship_type,direction,confidence,evidence_url,evidence_summary,evidence_date,valid_from,valid_until) VALUES(?,?,?,?,?,?,?,?,?,?)",
   fromId,toId,input.relationshipType(),direction,input.confidence(),evidence,input.evidenceSummary(),
   Date.valueOf(input.evidenceDate()),input.validFrom()==null?null:Date.valueOf(input.validFrom()),
   input.validUntil()==null?null:Date.valueOf(input.validUntil()));
  List<Edge> matching=jdbc.query(
   "SELECT r.id,a.symbol,b.symbol,r.relationship_type,r.direction,r.confidence,r.evidence_url,r.evidence_summary,r.evidence_date,r.valid_from,r.valid_until,r.status FROM company_relationships r JOIN assets a ON a.id=r.from_asset_id JOIN assets b ON b.id=r.to_asset_id WHERE r.from_asset_id=? AND r.to_asset_id=? AND r.relationship_type=? AND r.evidence_url=?",
   (r,i)->map(r),fromId,toId,input.relationshipType(),evidence);
  return matching.get(0);
 }
 private Long lookup(String ticker){
  List<Long> ids=jdbc.query("SELECT id FROM assets WHERE symbol=?",(r,i)->r.getLong(1),ticker);
  if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Import referenced assets first");
  return ids.get(0);
 }
 private static Edge map(java.sql.ResultSet r)throws java.sql.SQLException{
  return new Edge(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
   r.getString(6),r.getString(7),r.getString(8),r.getDate(9).toLocalDate(),
   r.getDate(10)==null?null:r.getDate(10).toLocalDate(),
   r.getDate(11)==null?null:r.getDate(11).toLocalDate(),r.getString(12));
 }
 @Transactional(readOnly=true)
 public List<Edge> direct(String rawSymbol,boolean includeRetracted){
  String ticker=symbol(rawSymbol);lookup(ticker);
  String sql="SELECT r.id,a.symbol,b.symbol,r.relationship_type,r.direction,r.confidence,r.evidence_url,r.evidence_summary,r.evidence_date,r.valid_from,r.valid_until,r.status FROM company_relationships r JOIN assets a ON a.id=r.from_asset_id JOIN assets b ON b.id=r.to_asset_id WHERE (a.symbol=? OR b.symbol=?)";
  if(!includeRetracted)sql+=" AND r.status='ACTIVE' AND (r.valid_from IS NULL OR r.valid_from<=CURRENT_DATE) AND (r.valid_until IS NULL OR r.valid_until>=CURRENT_DATE)";
  sql+=" ORDER BY r.id LIMIT 500";
  return jdbc.query(sql,(r,i)->map(r),ticker,ticker);
 }
 @Transactional(readOnly=true)
 public Graph graph(String rawSymbol,int depth){
  String root=symbol(rawSymbol);
  if(depth<1||depth>2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Graph depth must be 1 or 2");
  lookup(root);
  Map<String,Node> nodes=new LinkedHashMap<>();
  Map<Long,Edge> edges=new LinkedHashMap<>();
  Deque<Work> queue=new ArrayDeque<>();
  Set<String> expanded=new HashSet<>();
  queue.add(new Work(root,0));
  while(!queue.isEmpty()){
   Work current=queue.removeFirst();
   if(!expanded.add(current.symbol()))continue;
   nodes.putIfAbsent(current.symbol(),node(current.symbol()));
   if(current.depth()>=depth)continue;
   for(Edge edge:direct(current.symbol(),false)){
    // Directed edges can be navigated in either direction for discovery,
    // but the original direction remains visible in each edge.
    edges.putIfAbsent(edge.id(),edge);
    String neighbor=edge.fromSymbol().equals(current.symbol())?edge.toSymbol():edge.fromSymbol();
    nodes.putIfAbsent(neighbor,node(neighbor));
    if(nodes.size()>200||edges.size()>500)
     throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Graph too large; narrow the query");
    if(!expanded.contains(neighbor))queue.addLast(new Work(neighbor,current.depth()+1));
   }
  }
  return new Graph(root,depth,List.copyOf(nodes.values()),List.copyOf(edges.values()),
   "Business links are sourced assertions, not measured price correlations or causal predictions.");
 }
 private Node node(String ticker){
  return jdbc.queryForObject("SELECT symbol,name FROM assets WHERE symbol=?",
    (r,i)->new Node(r.getString(1),r.getString(2)),ticker);
 }
 @Transactional
 public Edge retract(long id){
  if(id<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid relationship ID");
  int updated=jdbc.update("UPDATE company_relationships SET status='RETRACTED',updated_time=CURRENT_TIMESTAMP WHERE id=? AND status='ACTIVE'",id);
  if(updated==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active relationship not found");
  return jdbc.queryForObject(
   "SELECT r.id,a.symbol,b.symbol,r.relationship_type,r.direction,r.confidence,r.evidence_url,r.evidence_summary,r.evidence_date,r.valid_from,r.valid_until,r.status FROM company_relationships r JOIN assets a ON a.id=r.from_asset_id JOIN assets b ON b.id=r.to_asset_id WHERE r.id=?",
   (r,i)->map(r),id);
 }
}
