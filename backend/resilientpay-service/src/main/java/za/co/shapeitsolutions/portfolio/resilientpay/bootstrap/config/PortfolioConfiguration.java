package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.AudienceValidator;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.PortfolioTokenService;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.in.PortfolioSessions;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.PortfolioSessionStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;
import za.co.shapeitsolutions.portfolio.resilientpay.application.service.PortfolioSessionApplicationService;

/**
 * Composes the explicit synthetic portfolio profile without enabling legacy demo token issuance.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
@Profile("portfolio")
@EnableConfigurationProperties(PortfolioProperties.class)
public final class PortfolioConfiguration {
    /** Prevents accidentally combining hosted portfolio and permissive local/test profiles. */
    public PortfolioConfiguration(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("demo", "test"))) {
            throw new IllegalStateException("Portfolio must not be combined with demo or test profiles");
        }
    }

    /** Selects application-owned atomic session transactions. */
    @Bean
    PortfolioSessions portfolioSessions(PortfolioSessionStore store, TransactionBoundary transactions, Clock clock) {
        return new PortfolioSessionApplicationService(store, transactions, clock);
    }

    /** Verifies independent portfolio issuer, signature, audience and timestamps. */
    @Bean
    JwtDecoder portfolioJwtDecoder(PortfolioProperties portfolio, ResilientPaySecurityProperties security) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(
                        new SecretKeySpec(HexFormat.of().parseHex(portfolio.jwtKey()), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(PortfolioTokenService.ISSUER),
                new AudienceValidator(security.audience())));
        return decoder;
    }
}
