package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.PortfolioTestKeys;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ActorContextFactory;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ApiExceptionHandler;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.PortfolioSessionController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReversalCommandController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.WebCommandMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.PortfolioSession;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.ReversalOperations;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.PortfolioSessionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.PortfolioConfiguration;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPayHttpProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Exercises actual MVC, signature verification and portfolio cookie/origin/revocation binding.
 * A memory session boundary isolates these HTTP tests; PostgreSQL quota/isolation proofs are separate.
 *
 * @author Daniel Jr Nkulu
 */
class PortfolioHttpSecurityTest {
    private static final String ORIGIN = "https://portfolio.example.test";
    private static final String ACCESS_KEY = PortfolioTestKeys.sequentialHex(0);
    private static final String JWT_KEY = PortfolioTestKeys.sequentialHex(64);
    private static final String ENDPOINT = "/api/v1/portfolio/session";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;
    private AdjustableClock clock;

    @BeforeEach
    void startActualPortfolioBoundary() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("portfolio");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "resilientpay.portfolio.origin=" + ORIGIN,
                "resilientpay.portfolio.access-cookie-key=" + ACCESS_KEY,
                "resilientpay.portfolio.jwt-key=" + JWT_KEY);
        context.register(Fixture.class);
        context.refresh();
        clock = context.getBean(AdjustableClock.class);
        http = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void closeBoundary() {
        context.close();
    }

    @Test
    void outerGateAndExactOriginAreRequiredBeforeWorkspaceCreation() throws Exception {
        assertEquals(401, exchange(post(ENDPOINT).header("Origin", ORIGIN)).getStatus());
        String gate = gate();
        for (String origin :
                List.of("https://attacker.example", ORIGIN + "/", "null", "https://portfolio.example.test:444")) {
            assertEquals(
                    403,
                    exchange(post(ENDPOINT).header("Origin", origin).header("Cookie", gate))
                            .getStatus());
        }
        assertEquals(403, exchange(post(ENDPOINT).header("Cookie", gate)).getStatus());
        assertEquals(
                403,
                exchange(post(ENDPOINT).header("Origin", ORIGIN, ORIGIN).header("Cookie", gate))
                        .getStatus());
        assertEquals(
                401,
                exchange(mutate(post(ENDPOINT), gate.substring(0, gate.length() - 2) + "zz"))
                        .getStatus());
        assertEquals(
                400,
                exchange(mutate(post(ENDPOINT), gate)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tenantId\":\"00000000-0000-0000-0000-000000000001\"}"))
                        .getStatus());
    }

    @Test
    void workspaceCookieIsProtectedAndExistingWorkspaceIsNotSilentlyRotated() throws Exception {
        String gate = gate();
        MockHttpServletResponse created = exchange(mutate(post(ENDPOINT), gate));
        assertEquals(201, created.getStatus());
        String cookie = created.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("Secure; HttpOnly; SameSite=Strict"));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("Max-Age=7200"));
        assertFalse(cookie.contains("Domain="));
        assertEquals("no-store", created.getHeader(HttpHeaders.CACHE_CONTROL));
        String body = created.getContentAsString();
        assertFalse(body.contains("tenant"));
        assertFalse(body.contains("accessToken"));
        assertFalse(body.contains("cookie"));
        String cookies = gate + "; " + cookie.split(";", 2)[0];
        MockHttpServletResponse existing = exchange(mutate(post(ENDPOINT), cookies));
        assertEquals(200, existing.getStatus());
        assertEquals(body, existing.getContentAsString());
        assertEquals(200, exchange(get(ENDPOINT).header("Cookie", cookies)).getStatus());
    }

    @Test
    void everyPersonaHasDistinctSubjectBoundedScopesAndIndependentPortfolioIssuer() throws Exception {
        String cookies = workspace(gate());
        Map<DemoRole, String> subjects = new HashMap<>();
        for (DemoRole role : DemoRole.values()) {
            SignedJWT jwt = SignedJWT.parse(persona(cookies, role));
            subjects.put(role, jwt.getJWTClaimsSet().getSubject());
            assertEquals(PortfolioTokenService.ISSUER, jwt.getJWTClaimsSet().getIssuer());
            assertEquals(role.scopes(), jwt.getJWTClaimsSet().getStringClaim("scope"));
            assertEquals(role.assuranceLevel(), jwt.getJWTClaimsSet().getStringClaim("acr"));
            assertEquals(
                    clock.instant().plusSeconds(600),
                    jwt.getJWTClaimsSet().getExpirationTime().toInstant());
            assertNotEquals(
                    DemoTokenService.DEMO_TENANT_ID.toString(),
                    jwt.getJWTClaimsSet().getStringClaim("tenant_id"));
        }
        assertEquals(4, subjects.values().stream().distinct().count());
        assertEquals(
                400,
                exchange(mutate(post(ENDPOINT + "/persona"), cookies)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"PLATFORM_ROOT\"}"))
                        .getStatus());
        assertEquals(404, exchange(mutate(post("/api/v1/demo/token"), cookies)).getStatus());
    }

    @Test
    void validBearerCannotBeUsedWithoutItsOwnTwoBrowserCredentials() throws Exception {
        String first = workspace(gate());
        String token = persona(first, DemoRole.REVERSAL_OPERATOR);
        assertEquals(200, commands(first, token).getStatus());
        assertEquals(401, commands(null, token).getStatus());
        String second = workspace(gate());
        assertEquals(401, commands(second, token).getStatus());
        String workspacePart = first.substring(first.indexOf(';') + 2);
        assertEquals(401, commands(gate() + "; " + workspacePart, token).getStatus());
        assertEquals(401, commands(first + "; " + workspacePart, token).getStatus());
    }

    @Test
    void resetRevokesOldBearerRotatesTenantAndRetainsHistoricalWorkspace() throws Exception {
        String cookies = workspace(gate());
        SignedJWT old = SignedJWT.parse(persona(cookies, DemoRole.REVERSAL_OPERATOR));
        MockHttpServletResponse reset = exchange(mutate(post(ENDPOINT + "/reset"), cookies));
        assertEquals(201, reset.getStatus());
        String replacement = cookies.split(";", 2)[0] + "; "
                + reset.getHeader(HttpHeaders.SET_COOKIE).split(";", 2)[0];
        SignedJWT fresh = SignedJWT.parse(persona(replacement, DemoRole.REVERSAL_OPERATOR));
        assertNotEquals(
                old.getJWTClaimsSet().getStringClaim("tenant_id"),
                fresh.getJWTClaimsSet().getStringClaim("tenant_id"));
        assertEquals(401, commands(cookies, old.serialize()).getStatus());
        assertEquals(401, commands(replacement, old.serialize()).getStatus());
        assertEquals(200, commands(replacement, fresh.serialize()).getStatus());
        assertEquals(2, context.getBean(MemorySessions.class).rows.size());
    }

    @Test
    void logoutAndAbsoluteExpiryRevokeStillSignatureValidBearerTokens() throws Exception {
        String cookies = workspace(gate());
        String token = persona(cookies, DemoRole.REVERSAL_OPERATOR);
        MockHttpServletResponse logout = exchange(mutate(post(ENDPOINT + "/logout"), cookies));
        assertEquals(204, logout.getStatus());
        assertTrue(logout.getHeader(HttpHeaders.SET_COOKIE).contains("Max-Age=0"));
        assertEquals(401, commands(cookies, token).getStatus());
        String second = workspace(gate());
        String secondToken = persona(second, DemoRole.REVERSAL_OPERATOR);
        clock.now = clock.now.plusSeconds(7201);
        assertEquals(401, commands(second, secondToken).getStatus());
    }

    @Test
    void signedWrongPurposeFutureExpiredAndWrongLifetimeGateCookiesAreRejected() throws Exception {
        long now = clock.instant().getEpochSecond();
        for (String gate : List.of(
                gate("csrf", now, now + 28800),
                gate("session", now + 1, now + 28801),
                gate("session", now - 28801, now - 1),
                gate("session", now, now + 28801))) {
            assertEquals(401, exchange(mutate(post(ENDPOINT), gate)).getStatus());
        }
    }

    @Test
    void workspaceAndBearerCannotOutliveThePasswordSessionOrExtendItsDeadlineOnReset() throws Exception {
        long now = clock.instant().getEpochSecond();
        String cookies = workspace(gate("session", now - 28500, now + 300));
        MockHttpServletResponse current = exchange(get(ENDPOINT).header("Cookie", cookies));
        assertEquals(
                clock.instant().plusSeconds(300).toString(),
                JSON.readTree(current.getContentAsString()).get("expiresAt").asText());
        SignedJWT token = SignedJWT.parse(persona(cookies, DemoRole.REVERSAL_OPERATOR));
        assertEquals(
                clock.instant().plusSeconds(300),
                token.getJWTClaimsSet().getExpirationTime().toInstant());
        clock.now = clock.now.plusSeconds(100);
        MockHttpServletResponse reset = exchange(mutate(post(ENDPOINT + "/reset"), cookies));
        assertEquals(201, reset.getStatus());
        assertEquals(
                Instant.ofEpochSecond(now + 300).toString(),
                JSON.readTree(reset.getContentAsString()).get("expiresAt").asText());
    }

    private MockHttpServletResponse commands(String cookies, String token) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/v1/reversalcommands").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        return exchange(request);
    }

    private String persona(String cookies, DemoRole role) throws Exception {
        MockHttpServletResponse response = exchange(mutate(post(ENDPOINT + "/persona"), cookies)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"));
        assertEquals(200, response.getStatus());
        return JSON.readTree(response.getContentAsString()).get("accessToken").asText();
    }

    private String workspace(String gate) throws Exception {
        MockHttpServletResponse response = exchange(mutate(post(ENDPOINT), gate));
        assertEquals(201, response.getStatus());
        return gate + "; " + response.getHeader(HttpHeaders.SET_COOKIE).split(";", 2)[0];
    }

    private String gate() throws Exception {
        long issued = clock.instant().getEpochSecond();
        return gate("session", issued, issued + 28800);
    }

    private String gate(String purpose, long issued, long expiry) throws Exception {
        String payload = "v1|" + purpose + "|" + issued + "|" + expiry + "|"
                + UUID.randomUUID().toString().replace("-", "");
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encoded = encoder.encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(ACCESS_KEY), "HmacSHA256"));
        return PortfolioRequestSecurity.ACCESS_COOKIE + "=" + encoded + "."
                + encoder.encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
    }

    private static MockHttpServletRequestBuilder mutate(MockHttpServletRequestBuilder request, String cookies) {
        return request.header("Origin", ORIGIN).header("Cookie", cookies);
    }

    private MockHttpServletResponse exchange(MockHttpServletRequestBuilder request) throws Exception {
        return http.perform(request).andReturn().getResponse();
    }

    /**
     * Wires the real portfolio security, controller and application transaction selection in memory.
     *
     * @author Daniel Jr Nkulu
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
        SecurityConfiguration.class,
        PortfolioConfiguration.class,
        PortfolioRequestSecurity.class,
        PortfolioTokenService.class,
        PortfolioSessionController.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiExceptionHandler.class,
        ReversalCommandController.class,
        ActorContextFactory.class,
        WebCommandMapper.class
    })
    static class Fixture {
        @Bean
        AdjustableClock clock() {
            return new AdjustableClock();
        }

        @Bean
        MemorySessions store() {
            return new MemorySessions();
        }

        @Bean
        ResilientPayHttpProperties httpProperties() {
            return new ResilientPayHttpProperties(List.of(ORIGIN));
        }

        @Bean
        ResilientPaySecurityProperties securityProperties() {
            return new ResilientPaySecurityProperties(
                    "resilientpay-api", "independent-evidence-test-key", "test", "urn:resilientpay:aal2");
        }

        @Bean
        ReversalOperations operations() {
            ReversalOperations operations = mock(ReversalOperations.class);
            when(operations.listReversals(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());
            return operations;
        }

        @Bean
        TransactionBoundary transactions() {
            return new TransactionBoundary() {
                @Override
                public <T> T required(Supplier<T> work) {
                    return work.get();
                }

                @Override
                public <T> T readOnly(Supplier<T> work) {
                    return work.get();
                }

                @Override
                public <T> T requiresNew(Supplier<T> work) {
                    return work.get();
                }
            };
        }
    }

    /**
     * Supplies deterministic absolute expiry without bypassing actual JWT cryptography.
     *
     * @author Daniel Jr Nkulu
     */
    static final class AdjustableClock extends Clock {
        private Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Provides only the session port for HTTP tests, retaining revoked records for reset assertions.
     *
     * @author Daniel Jr Nkulu
     */
    static final class MemorySessions implements PortfolioSessionStore {
        private final Map<String, PortfolioSession> rows = new HashMap<>();
        private final java.util.Set<String> revoked = new java.util.HashSet<>();

        @Override
        public PortfolioSession create(PortfolioSession session, Instant now) {
            rows.put(session.cookieHash(), session);
            return session;
        }

        @Override
        public PortfolioSession active(String cookieHash, String gateHash, Instant now) {
            PortfolioSession session = rows.get(cookieHash);
            if (session == null
                    || revoked.contains(cookieHash)
                    || !session.gateHash().equals(gateHash)
                    || !session.expiresAt().isAfter(now)) {
                throw new ApplicationException(
                        ApplicationErrorCode.AUTHENTICATION_REQUIRED, "Inactive synthetic session");
            }
            return session;
        }

        @Override
        public PortfolioSession reset(String oldCookieHash, PortfolioSession replacement, Instant now) {
            active(oldCookieHash, replacement.gateHash(), now);
            revoked.add(oldCookieHash);
            return create(replacement, now);
        }

        @Override
        public void logout(String cookieHash, String gateHash, Instant now) {
            active(cookieHash, gateHash, now);
            revoked.add(cookieHash);
        }
    }
}
