package lu.bourse.engine.service;
import lu.bourse.engine.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
@Service
public class HistoryService {
 private final AssetRepository assets;
 private final MarketCandleRepository candles;
 public HistoryService(AssetRepository assets,MarketCandleRepository candles){this.assets=assets;this.candles=candles;}
 public record PriceBar(Instant openTime,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,BigDecimal volume) {}
 public record ImportRequest(String symbol,String name,String assetType,String currency,String timeframe,String provider,List<PriceBar> bars) {}
 public record ImportResult(String symbol,int inserted,int skipped) {}
 public record Analysis(String symbol,String timeframe,Instant lastCandleTime,int candleCount,BigDecimal latestClose,FinancialIndicators.Result indicators,String provenance) {}
 @Transactional
 public ImportResult importBars(ImportRequest request){
  if(request==null || request.symbol()==null || !request.symbol().matches("[A-Za-z0-9._-]{1,40}") || request.name()==null || request.name().isBlank() || request.name().length()>255 || request.currency()==null || !request.currency().matches("[A-Z]{3}") || !Set.of("STOCK","CRYPTO").contains(request.assetType()) || !Set.of("1d","1h","1m").contains(request.timeframe()) || request.provider()==null || !request.provider().matches("[A-Za-z0-9._-]{1,64}") || request.bars()==null || request.bars().isEmpty() || request.bars().size()>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid import request");
  String symbol=request.symbol().toUpperCase(Locale.ROOT);
  AssetEntity asset=assets.findBySymbol(symbol).orElse(null);
  if(asset==null) asset=assets.save(new AssetEntity(symbol,request.name(),request.assetType(),request.currency()));
  else if(!asset.getCurrency().equals(request.currency()) || !asset.getAssetType().equals(request.assetType())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Existing asset type/currency differs");
  int inserted=0,skipped=0;
  Set<Instant> seen=new HashSet<>();
  for(PriceBar b:request.bars()) {
   if(b==null || b.openTime()==null || b.openTime().isAfter(Instant.now()) || b.open()==null || b.high()==null || b.low()==null || b.close()==null || b.volume()==null || b.open().signum()<=0 || b.high().compareTo(b.open())<0 || b.high().compareTo(b.close())<0 || b.low().compareTo(b.open())>0 || b.low().compareTo(b.close())>0 || b.low().signum()<=0 || b.volume().signum()<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid OHLCV bar");
   if(!seen.add(b.openTime()) || candles.existsByAssetIdAndTimeframeAndOpenTime(asset.getId(),request.timeframe(),b.openTime())) { skipped++;continue; }
   candles.save(new MarketCandleEntity(asset,request.timeframe(),b.openTime(),b.open(),b.high(),b.low(),b.close(),b.volume(),request.provider())); inserted++;
  }
  return new ImportResult(symbol,inserted,skipped);
 }
 @Transactional(readOnly=true)
 public Analysis analyze(String rawSymbol,String timeframe){
  if(rawSymbol==null || !rawSymbol.matches("[A-Za-z0-9._-]{1,40}") || !Set.of("1d","1h","1m").contains(timeframe)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid symbol or timeframe");
  String symbol=rawSymbol.toUpperCase(Locale.ROOT);
  List<MarketCandleEntity> bars=candles.findTop250ByAssetSymbolAndTimeframeOrderByOpenTimeDesc(symbol,timeframe);
  if(bars.size()<35) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"At least 35 completed candles required; found "+bars.size());
  Collections.reverse(bars);
  List<BigDecimal> prices=bars.stream().map(MarketCandleEntity::getClosePrice).toList();
  return new Analysis(symbol,timeframe,bars.get(bars.size()-1).getOpenTime(),bars.size(),prices.get(prices.size()-1),FinancialIndicators.calculate(prices),"IMPORTED_DATA_NOT_VERIFIED");
 }
}
