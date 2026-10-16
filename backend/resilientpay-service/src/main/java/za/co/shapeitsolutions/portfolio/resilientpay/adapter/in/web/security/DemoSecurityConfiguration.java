package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Installs a process-local demo JWT decoder only for demo and isolated test profiles.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
@Profile({"demo", "test"})
public class DemoSecurityConfiguration {

    /** Creates an HS256 decoder with issuer, timestamps, and audience validation. */
    @Bean
    JwtDecoder demoJwtDecoder(ResilientPaySecurityProperties properties) {
        SecretKeySpec key = new SecretKeySpec(DemoTokenService.deriveKey(properties.evidenceHmacKey()), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(DemoTokenService.ISSUER),
                new AudienceValidator(properties.audience())));
        return decoder;
    }
}
