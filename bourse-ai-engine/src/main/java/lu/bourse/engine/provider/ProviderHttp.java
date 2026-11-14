package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProviderHttp {
 private final RestClient client=RestClient.builder().build();
 private final ObjectMapper mapper;
 public ProviderHttp(ObjectMapper mapper){this.mapper=mapper;}
 public JsonNode get(String url,String userAgent){
  try {
   String body=client.get().uri(url).headers(h->{if(userAgent!=null && !userAgent.isBlank())h.set(HttpHeaders.USER_AGENT,userAgent);}).retrieve().body(String.class);
   if(body==null || body.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Empty provider response");
   JsonNode json=mapper.readTree(body);
   if(json.isObject() && json.has("status") && "error".equalsIgnoreCase(json.path("status").asText()))
     throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Provider error: "+json.path("message").asText("unknown"));
   return json;
  } catch(ResponseStatusException e){throw e;}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Provider unavailable or invalid response: "+e.getClass().getSimpleName(),e);}
 }
}
