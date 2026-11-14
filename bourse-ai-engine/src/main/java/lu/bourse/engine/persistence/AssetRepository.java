package lu.bourse.engine.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface AssetRepository extends JpaRepository<AssetEntity,Long> {
 Optional<AssetEntity> findBySymbol(String symbol);
}
