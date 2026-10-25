package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.DemoRole;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.DemoTokenResponse;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.PortfolioRequestSecurity;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.PortfolioTokenService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.PortfolioSessions;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ConditionalOnRuntimeRole;

/**
 * Provides password-gated isolated synthetic workspaces and explicit bounded persona switching.
 *
 * @author Daniel Jr Nkulu
 */
@RestController
@Profile("portfolio")
@ConditionalOnRuntimeRole("api")
@RequestMapping("/api/v1/portfolio/session")
public final class PortfolioSessionController {
    private final PortfolioSessions sessions;
    private final PortfolioRequestSecurity security;
    private final PortfolioTokenService tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Creates the hosted visitor transport without accepting arbitrary tenant identities. */
    public PortfolioSessionController(
            PortfolioSessions sessions, PortfolioRequestSecurity security, PortfolioTokenService tokens, Clock clock) {
        this.sessions = sessions;
        this.security = security;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Returns the active workspace and available synthetic personas without issuing a bearer. */
    @GetMapping
    public ResponseEntity<SessionResponse> current(HttpServletRequest request) {
        return noStore().body(view(security.session(request)));
    }

    /** Creates a bounded workspace, or returns this browser's existing active workspace unchanged. */
    @PostMapping
    public ResponseEntity<SessionResponse> create(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        security.requireOrigin(request);
        if (body != null && !body.isEmpty()) {
            throw new ApplicationException(
                    ApplicationErrorCode.INVALID_REQUEST,
                    "Portfolio workspace creation does not accept caller-selected identities");
        }
        PortfolioRequestSecurity.GateIdentity gate = security.gate(request);
        try {
            return noStore().body(view(security.session(request)));
        } catch (ApplicationException exception) {
            if (exception.code() != ApplicationErrorCode.AUTHENTICATION_REQUIRED) {
                throw exception;
            }
        }
        String credential = credential();
        PortfolioSession session =
                sessions.create(PortfolioRequestSecurity.digest(credential), gate.hash(), gate.expiresAt());
        return noStore(201)
                .header(HttpHeaders.SET_COOKIE, cookie(credential, session.expiresAt()))
                .body(view(session));
    }

    /** Issues an independently signed bounded persona token for this active browser workspace. */
    @PostMapping("/persona")
    public ResponseEntity<DemoTokenResponse> persona(
            HttpServletRequest request, @Valid @RequestBody PersonaRequest body) {
        security.requireOrigin(request);
        return noStore().body(new DemoTokenResponse(tokens.issue(security.session(request), body.role())));
    }

    /** Rotates to fresh seed entities while preserving old audit, idempotency and in-flight work. */
    @PostMapping("/reset")
    public ResponseEntity<SessionResponse> reset(HttpServletRequest request) {
        security.requireOrigin(request);
        PortfolioRequestSecurity.GateIdentity gate = security.gate(request);
        String credential = credential();
        PortfolioSession replacement = sessions.reset(
                security.workspaceHash(request), PortfolioRequestSecurity.digest(credential), gate.hash());
        return noStore(201)
                .header(HttpHeaders.SET_COOKIE, cookie(credential, replacement.expiresAt()))
                .body(view(replacement));
    }

    /** Revokes the server-side workspace and clears its browser credential. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        security.requireOrigin(request);
        sessions.logout(security.workspaceHash(request), security.gate(request).hash());
        return noStore(204)
                .header(
                        HttpHeaders.SET_COOKIE,
                        PortfolioRequestSecurity.WORKSPACE_COOKIE
                                + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0")
                .build();
    }

    private String credential() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private String cookie(String value, Instant expiry) {
        long remaining = Math.max(1, expiry.getEpochSecond() - clock.instant().getEpochSecond());
        return PortfolioRequestSecurity.WORKSPACE_COOKIE + "=" + value
                + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + remaining;
    }

    private static SessionResponse view(PortfolioSession session) {
        return new SessionResponse(
                session.id(),
                session.expiresAt(),
                Arrays.stream(DemoRole.values()).map(Enum::name).toList(),
                "ACTIVE");
    }

    private static ResponseEntity.BodyBuilder noStore() {
        return noStore(200);
    }

    private static ResponseEntity.BodyBuilder noStore(int status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache");
    }

    /**
     * Exposes safe workspace metadata, never tenant selectors or cookie credentials.
     *
     * @param sessionId active opaque identity
     * @param expiresAt absolute deadline, at most two hours after creation
     * @param roles exact bounded synthetic personas
     * @param state explicit active lifecycle state
     * @author Daniel Jr Nkulu
     */
    public record SessionResponse(UUID sessionId, Instant expiresAt, List<String> roles, String state) {
        public SessionResponse {
            roles = List.copyOf(roles);
        }
    }

    /**
     * Accepts one allowlisted persona, without caller-defined scopes, tenants or assurance claims.
     *
     * @param role bounded synthetic persona
     * @author Daniel Jr Nkulu
     */
    public record PersonaRequest(@NotNull DemoRole role) {}
}
