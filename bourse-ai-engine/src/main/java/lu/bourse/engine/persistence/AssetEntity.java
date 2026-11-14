package lu.bourse.engine.persistence;
import jakarta.persistence.*;
@Entity
@Table(name="assets")
public class AssetEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=40) private String symbol;
 @Column(nullable=false) private String name;
 @Column(name="asset_type",nullable=false,length=16) private String assetType;
 @Column(nullable=false,length=3) private String currency;
 @Column(name="created_time",insertable=false,updatable=false) private java.time.OffsetDateTime createdTime;
 @Column(name="updated_time",insertable=false,updatable=false) private java.time.OffsetDateTime updatedTime;
 public java.time.OffsetDateTime getCreatedTime(){return createdTime;}
 public java.time.OffsetDateTime getUpdatedTime(){return updatedTime;}
 protected AssetEntity() {}
 public AssetEntity(String symbol,String name,String assetType,String currency) { this.symbol=symbol;this.name=name;this.assetType=assetType;this.currency=currency; }
 public Long getId(){return id;}
 public String getSymbol(){return symbol;}
 public String getName(){return name;}
 public String getAssetType(){return assetType;}
 public String getCurrency(){return currency;}
}
