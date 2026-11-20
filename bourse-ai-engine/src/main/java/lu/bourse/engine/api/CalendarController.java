package lu.bourse.engine.api;
import lu.bourse.engine.calendar.MarketCalendarService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
@RestController
@RequestMapping("/api/v1/calendars")
public class CalendarController {
    private final MarketCalendarService calendars;
    public CalendarController(MarketCalendarService calendars){this.calendars=calendars;}
    @GetMapping public List<Map<String,Object>> list(){return calendars.calendars();}
    @PostMapping public void load(@RequestBody MarketCalendarService.CalendarImport r){calendars.importCalendar(r);}
    @GetMapping("/{code}/sessions") public List<Map<String,Object>> sessions(@PathVariable String code,@RequestParam LocalDate from,@RequestParam LocalDate to){return calendars.sessions(code,from,to);}
    @PutMapping("/assets/{symbol}") public void assign(@PathVariable String symbol,@RequestBody MarketCalendarService.Policy p){calendars.assign(symbol,p);}
    @GetMapping("/assets/{symbol}/state") public MarketCalendarService.State state(@PathVariable String symbol,@RequestParam(defaultValue="15m") String timeframe){return calendars.state(symbol,timeframe);}
}
