package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.ActorId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Derives tenant, pseudonymous actor, and authorities only from a validated access token.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class ActorContextFactory {

    /** Creates a fail-closed application context from one JWT authentication. */
    public ActorContext from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw authenticationFailure();
        }
        String tenantClaim = jwtAuthentication.getToken().getClaimAsString("tenant_id");
        String subject = jwtAuthentication.getToken().getSubject();
        String assurance = jwtAuthentication.getToken().getClaimAsString("acr");
        if (tenantClaim == null || subject == null || subject.isBlank()) {
            throw authenticationFailure();
        }
        try {
            Set<String> authorities = jwtAuthentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ActorContext(
                    new TenantId(UUID.fromString(tenantClaim)), new ActorId(subject), authorities, assurance);
        } catch (IllegalArgumentException exception) {
            throw authenticationFailure();
        }
    }

    private static ApplicationException authenticationFailure() {
        return new ApplicationException(
                ApplicationErrorCode.AUTHENTICATION_REQUIRED,
                "The validated token must contain a pseudonymous subject and tenant scope");
    }
}
