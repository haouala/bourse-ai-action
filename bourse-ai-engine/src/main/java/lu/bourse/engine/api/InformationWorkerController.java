package lu.bourse.engine.api;

import lu.bourse.engine.information.InformationWorkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Service credential only; public user APIs remain private development endpoints. */
@RestController
@RequestMapping("/api/internal/information")
public class InformationWorkerController {
    private final InformationWorkService work;private final String secret;
    public InformationWorkerController(InformationWorkService work,@Value("${information.ai.worker-token:}")String secret){this.work=work;this.secret=secret;}
    private void authorize(String authorization){if(secret.length()<32)throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Worker credential not configured");String supplied=authorization==null?"":authorization; if(!MessageDigest.isEqual(("Bearer "+secret).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(UNAUTHORIZED,"Worker credential required");}
    @PostMapping("/enqueue") public Map<String,Integer> enqueue(@RequestHeader(value="Authorization",required=false)String auth){authorize(auth);return Map.of("enqueued",work.enqueue());}
    @PostMapping("/claim") public Map<String,Object> claim(@RequestHeader(value="Authorization",required=false)String auth){authorize(auth);return work.claim();}
    @PutMapping("/jobs/{id}/content") public Map<String,Long> content(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader("X-Lease-Token")String lease,@PathVariable long id,@RequestBody InformationWorkService.Content c){authorize(auth);return Map.of("contentId",work.content(id,lease,c));}
    @PostMapping("/jobs/{id}/failure") public void fail(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader("X-Lease-Token")String lease,@PathVariable long id,@RequestBody InformationWorkService.Failure f){authorize(auth);work.fail(id,lease,f);}
}
