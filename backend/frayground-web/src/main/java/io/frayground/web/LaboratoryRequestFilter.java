package io.frayground.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Local management boundary and per-address token buckets. */
@Component
@Profile("laboratory")
public final class LaboratoryRequestFilter implements Filter {
  private final ConcurrentHashMap<String,Bucket> buckets=new ConcurrentHashMap<>();
  private final JsonMapper json=JsonMapper.builder().build();
  private static final class Bucket {
    double tokens; long last=System.nanoTime();
    Bucket(int capacity) { tokens=capacity; }
    synchronized boolean take(int rate,int capacity) {
      long now=System.nanoTime(); tokens=Math.min(capacity,tokens+(now-last)/1_000_000_000.0*rate); last=now;
      if(tokens<1)return false; tokens--; return true;
    }
  }
  @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
    var req=(HttpServletRequest)request; var res=(HttpServletResponse)response;
    if(!req.getRequestURI().startsWith("/api/v1/")) { chain.doFilter(request,response); return; }
    String origin=req.getHeader("Origin");
    if(origin!=null && !sameOrigin(req,origin)) { reject(res,403,"ORIGIN_FORBIDDEN"); return; }
    boolean mutation=!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD");
    if(mutation && !"laboratory".equals(req.getHeader("X-Frayground-Client"))) { reject(res,403,"CLIENT_HEADER_REQUIRED"); return; }
    if(mutation && (req.getContentLengthLong()>0 || req.getRequestURI().endsWith("/commands")) && (req.getContentType()==null ||
        !req.getContentType().toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?"))) {
      reject(res,415,"UNSUPPORTED_MEDIA_TYPE"); return;
    }
    int rate=mutation?10:50, cap=mutation?20:100;
    if(!buckets.computeIfAbsent(req.getRemoteAddr()+":"+mutation,k->new Bucket(cap)).take(rate,cap)) {
      res.setHeader("Retry-After","1"); reject(res,429,"RATE_LIMITED"); return;
    }
    chain.doFilter(request,response);
  }
  private static boolean sameOrigin(HttpServletRequest req,String value) {
    try {
      URI u=URI.create(value); int port=u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();
      return u.getUserInfo()==null && u.getRawPath().isEmpty() && u.getRawQuery()==null && u.getRawFragment()==null
          && req.getScheme().equalsIgnoreCase(u.getScheme()) && req.getServerName().equalsIgnoreCase(u.getHost()) && req.getServerPort()==port;
    } catch(RuntimeException e) { return false; }
  }
  private void reject(HttpServletResponse res,int status,String code) throws IOException {
    res.setStatus(status); res.setContentType("application/json");
    json.writeValue(res.getOutputStream(),LaboratoryErrors.transport(status,code).getBody());
  }
}
