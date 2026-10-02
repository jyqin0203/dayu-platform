package cn.edu.fudan.dayu.interfaces.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded Legacy JSON body replay lets Spring CSRF validate old csrf_token fields before controllers. */
final class LegacyJsonCsrfFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    LegacyJsonCsrfFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/api/auth.php") || !request.getMethod().equals("POST")
                || request.getContentType() == null || !request.getContentType().startsWith("application/json");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        byte[] bytes = request.getInputStream().readNBytes(16385);
        Map<String, Object> body;
        try {
            if (bytes.length > 16384) throw new IOException("body too large");
            body = mapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
            if (body == null) throw new IOException("object required");
        } catch (IOException ex) {
            response.setStatus(400); response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), Map.of("ok", false, "message", "Malformed request"));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getParameter(String name) {
                if (name.equals("csrf_token") && body.get(name) instanceof String token) return token;
                return super.getParameter(name);
            }
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    @Override public int read() { return input.read(); }
                };
            }
        }, response);
    }
}
