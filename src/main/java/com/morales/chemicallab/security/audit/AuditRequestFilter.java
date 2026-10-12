package com.morales.chemicallab.security.audit;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.security.SessionPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

/** Runs outside security so it observes the final public response, once per request. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
public class AuditRequestFilter extends OncePerRequestFilter {
    public static final String ACTOR=AuditRequestFilter.class.getName()+".actor";
    public static final String LOGIN_REJECTED=AuditRequestFilter.class.getName()+".loginRejected";
    private final AuditRejections audit;
    public AuditRequestFilter(AuditRejections audit){this.audit=audit;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        UUID id=UUID.randomUUID();response.setHeader("X-Correlation-ID",id.toString());
        try(var context=AuditContext.open(id)){
            try {chain.doFilter(request,response);}
            finally {
                int status=response.getStatus();String path=request.getRequestURI();
                if(path.startsWith("/api/") && (status==400 || status==401 || status==403 || status==409 || status==413 || status==429 || status>=500)
                        && !Boolean.TRUE.equals(request.getAttribute(LOGIN_REJECTED))) {
                    SessionPrincipal actor=request.getAttribute(ACTOR) instanceof SessionPrincipal p?p:null;
                    audit.record(status>=500?LogEventType.OPERATION_FAILED:LogEventType.REST_DENIED,actor,"REST",AuditSafety.requestedId(path),
                        "reason="+(status>=500?"HTTP_5XX":"HTTP_"+status),null);
                }
            }
        }
    }
}
