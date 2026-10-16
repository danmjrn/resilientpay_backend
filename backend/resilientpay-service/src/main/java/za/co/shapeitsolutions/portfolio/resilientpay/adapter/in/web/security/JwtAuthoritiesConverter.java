package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converts standard scopes and neutral role claims into Spring Security authorities.
 * It supports direct `roles` and Keycloak-compatible `realm_access.roles` without trusting tenant headers.
 *
 * @author Daniel Jr Nkulu
 */
public final class JwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private static final Map<String, Set<String>> ROLE_SCOPES = Map.of(
            "PAYMENT_ENGINEER",
            Set.of("reversals.write", "reversals.read", "reversals.retry", "transactions.read", "policies.read"),
            "OPERATIONS_ANALYST",
            Set.of("reversals.read", "audit.read", "reconciliation.read", "reconciliation.decide"),
            "APPROVER",
            Set.of("reversals.read", "audit.read", "reconciliation.read", "reconciliation.approve"),
            "PLATFORM_ADMIN",
            Set.of("transactions.write", "transactions.read", "policies.read"));

    @Override
    public Collection<GrantedAuthority> convert(Jwt source) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object scopeClaim = source.getClaims().get("scope");
        if (scopeClaim instanceof String scopes) {
            List.of(scopes.strip().split("\\s+")).stream()
                    .filter(scope -> !scope.isBlank())
                    .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                    .forEach(authorities::add);
        }
        addRolesAndMappedScopes(source.getClaims().get("roles"), authorities);
        Object realmAccess = source.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> realmMap) {
            addRolesAndMappedScopes(realmMap.get("roles"), authorities);
        }
        return authorities.stream().distinct().toList();
    }

    private void addRolesAndMappedScopes(Object rolesClaim, List<GrantedAuthority> authorities) {
        if (rolesClaim instanceof Collection<?> roles) {
            roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(role -> !role.isBlank())
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .forEach(authorities::add);
            roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(ROLE_SCOPES::get)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(Collection::stream)
                    .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                    .forEach(authorities::add);
        }
    }
}
