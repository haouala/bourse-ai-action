package lu.bourse.engine.api;

import jakarta.servlet.http.HttpServletRequest;
import lu.bourse.engine.news.NewsIngestionException;
import lu.bourse.engine.provider.quota.QuotaExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Stable, redactable error envelope; downstream Android can render severity and retry hints. */
@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
    public record ApiError(String code,String severity,String message,boolean retryable,
                           Long retryAfterSeconds,String requestId,Instant timestamp){}

    @ExceptionHandler(NewsIngestionException.class)
    public ResponseEntity<ApiError> news(NewsIngestionException e,HttpServletRequest request) {
        String message=switch(e.code()){
            case "GDELT_RATE_LIMIT" -> "GDELT limite temporairement les requêtes. La collecte reprendra plus tard.";
            case "GDELT_BATCH_NOT_PUBLISHED" -> "Cette archive GDELT n'est pas encore disponible. Réessayez plus tard.";
            case "GDELT_INDEX_UNAVAILABLE","GDELT_INDEX_INVALID","NGRAM_SLOT_UNAVAILABLE" ->
                "Impossible d'obtenir l'index GDELT pour le moment.";
            case "NEWS_SLOT_INVALID","NEWS_SLOT_OUT_OF_RANGE" -> "Horodatage du lot invalide ou hors période autorisée.";
            case "NEWS_METHOD_INVALID" -> "Choisissez NGRAMS, GKG ou RSS.";
            case "RSS_COLLECT_FAILED" -> "Le flux RSS ne répond pas actuellement. Le collecteur le réessaiera.";
            default -> "La source d'actualités est momentanément indisponible ou son archive est invalide.";
        };
        log.warn("api.news-error code={} status={} path={}",e.code(),e.status().value(),request.getRequestURI());
        return response(e.status(),e.code(),e.retryable()?"WARNING":"ERROR",message,
            e.retryable(),e.retryAfterSeconds()>0?e.retryAfterSeconds():null,request);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiError> quota(QuotaExceededException e,HttpServletRequest request) {
        long wait=e.retryAt()==null?3600:Math.max(1,Math.min(86400,Duration.between(Instant.now(),e.retryAt()).toSeconds()));
        log.warn("api.quota provider={} waitSeconds={} path={}",e.provider(),wait,request.getRequestURI());
        return response(HttpStatus.TOO_MANY_REQUESTS,"PROVIDER_QUOTA_EXCEEDED","WARNING",
            "Quota du fournisseur atteint. Le traitement reprendra après le délai indiqué.",
            true,wait,request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> parameter(MethodArgumentTypeMismatchException e,HttpServletRequest request) {
        log.info("api.invalid-parameter path={} parameter={}",request.getRequestURI(),e.getName());
        return response(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","ERROR",
            "Paramètre incorrect ou valeur non reconnue.",false,null,request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException e,HttpServletRequest request) {
        int code=e.getStatusCode().value();
        String safe=switch(code){
            case 400 -> "Paramètres invalides. Vérifiez les données envoyées.";
            case 401,403 -> "Accès non autorisé.";
            case 404 -> "Ressource introuvable. Vérifiez l'identifiant ou la version du serveur.";
            case 409 -> "Opération en conflit ou déjà en cours.";
            case 422 -> "Données insuffisantes ou incompatibles pour cette analyse.";
            case 429 -> "Limite de requêtes atteinte. Réessayez plus tard.";
            case 502,503,504 -> "Service externe indisponible. Réessayez plus tard.";
            default -> "Le serveur n'a pas pu traiter cette demande.";
        };
        boolean retry=code==429||code>=500;
        if(code>=500)log.error("api.failed status={} path={} type={}",code,request.getRequestURI(),e.getClass().getSimpleName());
        else log.info("api.rejected status={} path={}",code,request.getRequestURI());
        return response(HttpStatus.valueOf(code),"HTTP_"+code,retry?"WARNING":"ERROR",safe,retry,null,request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> payload(Exception e,HttpServletRequest request){
        log.info("api.invalid-payload path={} type={}",request.getRequestURI(),e.getClass().getSimpleName());
        return response(HttpStatus.BAD_REQUEST,"INVALID_PAYLOAD","ERROR",
            "Format JSON ou champs incorrects.",false,null,request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e,HttpServletRequest request) {
        // Never expose upstream bodies, exception messages, credentials, stack traces or SQL to clients.
        log.error("api.unexpected path={} type={}",request.getRequestURI(),e.getClass().getName(),e);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,"UNEXPECTED_ERROR","ERROR",
            "Erreur interne du serveur. Consultez ses journaux avec l'identifiant de demande.",
            false,null,request);
    }

    private static ResponseEntity<ApiError> response(HttpStatus status,String code,String severity,
        String message,boolean retryable,Long retryAfter,HttpServletRequest request) {
        String id=(String)request.getAttribute("bourse.request-id");
        if(id==null)id=UUID.randomUUID().toString();
        HttpHeaders headers=new HttpHeaders();
        headers.add("X-Request-ID",id);
        if(retryAfter!=null)headers.add(HttpHeaders.RETRY_AFTER,Long.toString(retryAfter));
        return new ResponseEntity<>(new ApiError(code,severity,message,retryable,retryAfter,id,Instant.now()),
            headers,status);
    }
}
