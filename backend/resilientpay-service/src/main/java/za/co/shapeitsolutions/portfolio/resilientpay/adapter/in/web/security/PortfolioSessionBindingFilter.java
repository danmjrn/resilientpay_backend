package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;

/**
 * Adds cookie/session revocation and same-origin enforcement to the portfolio bearer filter chain.
 * This filter is registered only inside Spring Security, never as a second servlet filter.
 *
 * @author Daniel Jr Nkulu
 */
public final class PortfolioSessionBindingFilter extends OncePerRequestFilter {
    private final PortfolioRequestSecurity security;

    /** Creates the explicit portfolio-only security filter. */
    public PortfolioSessionBindingFilter(PortfolioRequestSecurity security) {
        this.security = security;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            security.requireOrigin(request);
            security.gate(request);
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken bearer) {
                security.requireBearerSession(request, bearer.getToken());
            }
        } catch (ApplicationException exception) {
            int status = exception.code() == ApplicationErrorCode.ACCESS_DENIED ? 403 : 401;
            SecurityProblemWriter.write(
                    response,
                    status,
                    "Portfolio access denied",
                    exception.code().name());
            return;
        } catch (RuntimeException exception) {
            SecurityProblemWriter.write(
                    response, 503, "Portfolio session verification unavailable", "DEPENDENCY_UNAVAILABLE");
            return;
        }
        chain.doFilter(request, response);
    }
}
