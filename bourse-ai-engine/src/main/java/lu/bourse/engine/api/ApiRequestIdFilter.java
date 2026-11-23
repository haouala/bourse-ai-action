package lu.bourse.engine.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

/** Server-generated per-request correlation; never trust user-provided IDs or log credentials. */
@Component
public class ApiRequestIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                                                FilterChain chain)throws ServletException,IOException {
        String id=UUID.randomUUID().toString();
        request.setAttribute("bourse.request-id",id);
        response.setHeader("X-Request-ID",id);
        try {
            MDC.put("requestId",id);
            chain.doFilter(request,response);
        } finally { MDC.remove("requestId"); }
    }
}
