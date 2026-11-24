package lu.bourse.engine.provider;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class SecEdgarPeriodsTest {
    @Test
    void acceptsInstantQuarterAndYearToDateWithoutAcceptingInvertedPeriods() {
        var end = LocalDate.of(2026, 9, 30);
        var filed = end.plusDays(35);
        assertTrue(SecEdgarService.validPeriod(null, end, filed));
        assertTrue(SecEdgarService.validPeriod(end.minusMonths(3), end, filed));
        assertTrue(SecEdgarService.validPeriod(end.minusMonths(9), end, filed));
        assertFalse(SecEdgarService.validPeriod(end.plusDays(1), end, filed));
        assertFalse(SecEdgarService.validPeriod(null, end, end.minusDays(1)));
    }
}
