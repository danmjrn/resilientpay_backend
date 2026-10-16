package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Returns a stable, redacted 401 problem instead of Spring Security's default empty response.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authenticationException)
            throws IOException, ServletException {
        SecurityProblemWriter.write(response, 401, "Authentication required", "AUTHENTICATION_REQUIRED");
    }
}
