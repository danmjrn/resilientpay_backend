package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.PortfolioSessions;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.PortfolioProperties;

/**
 * Revalidates the independent password cookie, exact origin and active workspace/bearer binding.
 * No caller-supplied tenant or trusted-proxy identity header is accepted.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("portfolio")
public final class PortfolioRequestSecurity {
    /** Host-only cookie issued after the shared password challenge. */
    public static final String ACCESS_COOKIE = "__Host-resilientpay_access";
    /** Host-only opaque workspace credential, independently random from the outer cookie. */
    public static final String WORKSPACE_COOKIE = "__Host-resilientpay_workspace";

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final PortfolioProperties properties;
    private final PortfolioSessions sessions;
    private final Clock clock;
    private final byte[] accessKey;

    /** Creates the HTTP security boundary from independent keys and durable sessions. */
    public PortfolioRequestSecurity(PortfolioProperties properties, PortfolioSessions sessions, Clock clock) {
        this.properties = properties;
        this.sessions = sessions;
        this.clock = clock;
        this.accessKey = HexFormat.of().parseHex(properties.accessCookieKey());
    }

    /** Validates every API mutation against the configured same-origin browser boundary. */
    public void requireOrigin(HttpServletRequest request) {
        if (!List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            List<String> origins = Collections.list(request.getHeaders("Origin"));
            if (origins.size() != 1 || !properties.origin().equals(origins.getFirst())) {
                throw new ApplicationException(
                        ApplicationErrorCode.ACCESS_DENIED, "Portfolio mutations require the configured same origin");
            }
        }
    }

    /** Validates the exact existing gate token format and returns only its digest and deadline. */
    public GateIdentity gate(HttpServletRequest request) {
        String credential = cookie(request, ACCESS_COOKIE);
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{1,450}\\.[A-Za-z0-9_-]{43}")) {
            throw inactive();
        }
        try {
            String[] pieces = credential.split("\\.", -1);
            byte[] payload = DECODER.decode(pieces[0]);
            byte[] supplied = DECODER.decode(pieces[1]);
            if (!ENCODER.encodeToString(payload).equals(pieces[0])
                    || !ENCODER.encodeToString(supplied).equals(pieces[1])) {
                throw inactive();
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(accessKey, "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(pieces[0].getBytes(StandardCharsets.US_ASCII)), supplied)) {
                throw inactive();
            }
            String[] fields = new String(payload, StandardCharsets.US_ASCII).split("\\|", -1);
            if (fields.length != 5
                    || !"v1".equals(fields[0])
                    || !"session".equals(fields[1])
                    || !fields[2].matches("[0-9]{1,12}")
                    || !fields[3].matches("[0-9]{1,12}")
                    || !fields[4].matches("[a-f0-9]{32}")) {
                throw inactive();
            }
            long issued = Long.parseLong(fields[2]);
            long expires = Long.parseLong(fields[3]);
            long now = clock.instant().getEpochSecond();
            if (expires - issued != 28800 || now < issued || now >= expires) {
                throw inactive();
            }
            return new GateIdentity(digest(credential), Instant.ofEpochSecond(expires));
        } catch (IllegalArgumentException exception) {
            throw inactive();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required portfolio cryptography is unavailable", exception);
        }
    }

    /** Resolves the active server-owned tenant from both independent cookie credentials. */
    public PortfolioSession session(HttpServletRequest request) {
        return sessions.active(workspaceHash(request), gate(request).hash());
    }

    /** Derives an opaque credential digest without allowing a caller-selected session identifier. */
    public String workspaceHash(HttpServletRequest request) {
        String value = cookie(request, WORKSPACE_COOKIE);
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw inactive();
        }
        return digest(value);
    }

    /** Binds each already signature-validated bearer to this browser's active workspace. */
    public void requireBearerSession(HttpServletRequest request, Jwt token) {
        PortfolioSession session = session(request);
        try {
            List<String> roles = token.getClaimAsStringList("roles");
            String sessionId = token.getClaimAsString("sid");
            String tenantId = token.getClaimAsString("tenant_id");
            if (sessionId == null
                    || tenantId == null
                    || roles == null
                    || roles.size() != 1
                    || roles.getFirst() == null
                    || !session.id().equals(UUID.fromString(sessionId))
                    || !session.tenantId().equals(UUID.fromString(tenantId))
                    || !PortfolioTokenService.subject(session.id(), DemoRole.valueOf(roles.getFirst()))
                            .equals(token.getSubject())) {
                throw inactive();
            }
        } catch (IllegalArgumentException exception) {
            throw inactive();
        }
    }

    /** Returns at most one canonical occurrence of a named browser cookie. */
    static String cookie(HttpServletRequest request, String name) {
        List<String> headers = Collections.list(request.getHeaders("Cookie"));
        if (headers.size() != 1) {
            throw inactive();
        }
        String result = null;
        for (String part : headers.getFirst().split(";", -1)) {
            String item = part.strip();
            int separator = item.indexOf('=');
            if (separator > 0 && item.substring(0, separator).equals(name)) {
                if (result != null) {
                    throw inactive();
                }
                result = item.substring(separator + 1);
            }
        }
        return result;
    }

    /** Hashes credentials before they cross the application persistence boundary. */
    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static ApplicationException inactive() {
        return new ApplicationException(
                ApplicationErrorCode.AUTHENTICATION_REQUIRED, "An active portfolio browser session is required");
    }

    /**
     * Supplies no raw password-cookie value to downstream code.
     *
     * @param hash credential digest used for workspace binding
     * @param expiresAt validated password-session deadline
     * @author Daniel Jr Nkulu
     */
    public record GateIdentity(String hash, Instant expiresAt) {}
}
