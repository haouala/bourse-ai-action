package lu.bourse.engine.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
public interface MarketCandleRepository extends JpaRepository<MarketCandleEntity,Long> {
 Optional<MarketCandleEntity> findByAssetIdAndTimeframeAndOpenTime(Long assetId,String timeframe,Instant openTime);
 List<MarketCandleEntity> findTop250ByAssetSymbolAndTimeframeOrderByOpenTimeDesc(String symbol,String timeframe);
}
