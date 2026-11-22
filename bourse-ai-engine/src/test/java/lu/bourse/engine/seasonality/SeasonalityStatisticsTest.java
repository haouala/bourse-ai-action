package lu.bourse.engine.seasonality;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SeasonalityStatisticsTest {
    @Test void monthlyReturnUsesThePreviousMonthsCloseRatherThanTheFirstPrice(){
        var bars=new ArrayList<SeasonalityStatistics.Close>();bars.add(new SeasonalityStatistics.Close(LocalDate.of(2019,12,31),new BigDecimal("100")));
        for(LocalDate d=LocalDate.of(2020,1,1);d.isBefore(LocalDate.of(2020,3,1));d=d.plusDays(1))bars.add(new SeasonalityStatistics.Close(d,new BigDecimal(d.getMonthValue()==1?"110":"121")));
        var result=SeasonalityStatistics.calculate(bars,LocalDate.of(2020,3,1),false);
        assertThat(result.monthly().get(0).meanPercent()).isCloseTo(10.0,within(1e-8));assertThat(result.monthly().get(1).meanPercent()).isCloseTo(10.0,within(1e-8));
        assertThat(result.monthly().get(2).observations()).isZero();assertThat(result.monthly().get(2).meanPercent()).isNull();
    }
    @Test void unfinishedPeriodsAndFutureRowsAreNotIncluded(){
        var bars=series(LocalDate.of(2020,1,1),LocalDate.of(2026,12,31));var r=SeasonalityStatistics.calculate(bars,LocalDate.of(2026,11,19),false);
        assertThat(r.monthly().get(10).history()).noneMatch(x->x.year()==2026);
        assertThat(r.annual().getFirst().history()).noneMatch(x->x.year()==2026);
        assertThat(r.quarterly().get(3).history()).noneMatch(x->x.year()==2026);
    }
    @Test void firstPeriodWithoutPriorCloseAndLargeDataGapsAreRejected(){
        var bars=new ArrayList<SeasonalityStatistics.Close>();bars.add(new SeasonalityStatistics.Close(LocalDate.of(2020,2,29),BigDecimal.ONE));
        for(int i=1;i<=31;i++)if(i<10||i>20)bars.add(new SeasonalityStatistics.Close(LocalDate.of(2020,3,i),BigDecimal.TEN));
        var r=SeasonalityStatistics.calculate(bars,LocalDate.of(2020,4,1),false);assertThat(r.monthly().get(2).observations()).isZero();assertThat(r.rejectedPeriods()).isPositive();
    }
    @Test void annualSamplesCountYearsNotDailyObservations(){
        var r=SeasonalityStatistics.calculate(series(LocalDate.of(2010,1,1),LocalDate.of(2025,12,31)),LocalDate.of(2026,1,1),false);
        assertThat(r.annual().getFirst().observations()).isEqualTo(15);assertThat(r.annual().getFirst().reliability()).isEqualTo("HISTORICAL_ONLY");
        assertThat(r.monthly()).hasSize(12);assertThat(r.quarterly()).hasSize(4);assertThat(r.annual()).hasSize(1);
    }
    @Test void winFrequencyDoesNotConfuseAFlatPeriodWithAGain(){
        var bars=series(LocalDate.of(2010,1,1),LocalDate.of(2025,12,31)).stream().map(x->new SeasonalityStatistics.Close(x.date(),BigDecimal.TEN)).toList();
        var r=SeasonalityStatistics.calculate(bars,LocalDate.of(2026,1,1),false);assertThat(r.annual().getFirst().winFrequencyPercent()).isZero();assertThat(r.annual().getFirst().meanPercent()).isZero();
    }
    static List<SeasonalityStatistics.Close> series(LocalDate from,LocalDate to){var result=new ArrayList<SeasonalityStatistics.Close>();int i=0;for(LocalDate d=from;!d.isAfter(to);d=d.plusDays(1)){if(d.getDayOfWeek()==DayOfWeek.SATURDAY||d.getDayOfWeek()==DayOfWeek.SUNDAY)continue;result.add(new SeasonalityStatistics.Close(d,new BigDecimal("100").add(BigDecimal.valueOf(i++).movePointLeft(2))));}return result;}
}
