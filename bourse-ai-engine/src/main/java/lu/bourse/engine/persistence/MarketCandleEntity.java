package lu.bourse.engine.persistence;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
@Entity
@Table(name="market_candles",uniqueConstraints=@UniqueConstraint(name="uq_candle",columnNames={"asset_id","timeframe","open_time"}))
public class MarketCandleEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="asset_id",nullable=false) private AssetEntity asset;
 @Column(nullable=false,length=8) private String timeframe;
 @Column(name="open_time",nullable=false) private Instant openTime;
 @Column(name="open_price",nullable=false,precision=24,scale=10) private BigDecimal openPrice;
 @Column(name="high_price",nullable=false,precision=24,scale=10) private BigDecimal highPrice;
 @Column(name="low_price",nullable=false,precision=24,scale=10) private BigDecimal lowPrice;
 @Column(name="close_price",nullable=false,precision=24,scale=10) private BigDecimal closePrice;
 @Column(nullable=false,precision=28,scale=8) private BigDecimal volume;
 @Column(nullable=false,length=64) private String provider;
 @Column(name="created_time",insertable=false,updatable=false) private java.time.OffsetDateTime createdTime;
 @Column(name="updated_time",insertable=false,updatable=false) private java.time.OffsetDateTime updatedTime;
 public java.time.OffsetDateTime getCreatedTime(){return createdTime;}
 public java.time.OffsetDateTime getUpdatedTime(){return updatedTime;}
 protected MarketCandleEntity() {}
 public MarketCandleEntity(AssetEntity asset,String timeframe,Instant openTime,BigDecimal openPrice,BigDecimal highPrice,BigDecimal lowPrice,BigDecimal closePrice,BigDecimal volume,String provider){
 this.asset=asset;this.timeframe=timeframe;this.openTime=openTime;this.openPrice=openPrice;this.highPrice=highPrice;this.lowPrice=lowPrice;this.closePrice=closePrice;this.volume=volume;this.provider=provider;
 }
 public Instant getOpenTime(){return openTime;}
 public BigDecimal getClosePrice(){return closePrice;}
}
