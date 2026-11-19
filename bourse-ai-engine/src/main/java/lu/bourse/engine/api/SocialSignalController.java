package lu.bourse.engine.api;

import lu.bourse.engine.social.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/social")
public class SocialSignalController {
    private final SocialSignalService signals;private final XPublicationCollector x;
    public SocialSignalController(SocialSignalService signals,XPublicationCollector x){this.signals=signals;this.x=x;}
    @PostMapping("/actors") public Map<String,Long> actor(@RequestBody SocialSignalService.Actor a){return Map.of("id",signals.actor(a));}
    @GetMapping("/actors") public List<Map<String,Object>> actors(){return signals.actors();}
    @PostMapping("/actors/{id}/profiles") public Map<String,Long> profile(@PathVariable long id,@RequestBody SocialSignalService.Profile p){return Map.of("id",signals.profile(id,p));}
    @PostMapping("/actors/{id}/sync-x") public Map<String,Object> sync(@PathVariable long id){return x.sync(id);}
    @PostMapping("/publications") public Map<String,Long> publication(@RequestBody SocialSignalService.Publication p){return Map.of("id",signals.publication(p));}
    @GetMapping("/publications") public List<Map<String,Object>> publications(){return signals.publications();}
    @PostMapping("/events") public Map<String,Long> event(@RequestBody SocialSignalService.Event e){return Map.of("id",signals.event(e));}
    @GetMapping("/events") public List<Map<String,Object>> events(){return signals.events();}
    @PutMapping("/events/{id}/publications/{publicationId}") public void link(@PathVariable long id,@PathVariable long publicationId){signals.link(id,publicationId);}
    @PutMapping("/events/{id}/revision") public void revise(@PathVariable long id,@RequestBody SocialSignalService.Revision r){signals.revise(id,r);}
    @PutMapping("/events/{id}/assessment") public SocialSignalService.Score assess(@PathVariable long id,@RequestBody SocialSignalService.Assessment a){return signals.assess(id,a);}
    @PostMapping("/events/{id}/rescore") public List<SocialSignalService.Score> rescore(@PathVariable long id){return signals.rescore(id);}
    @GetMapping("/events/{id}/scores") public List<Map<String,Object>> scores(@PathVariable long id){return signals.scores(id);}
}
