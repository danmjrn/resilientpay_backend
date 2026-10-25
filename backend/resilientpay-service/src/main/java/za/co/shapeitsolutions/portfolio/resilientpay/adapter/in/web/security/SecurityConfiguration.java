package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPayHttpProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.ResilientPaySecurityProperties;

/**
 * Configures a stateless OIDC resource server, least-privilege HTTP access, and narrow CORS.
 * Application services repeat action authorization so a future non-HTTP adapter cannot bypass it.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    /** Builds the stateless API security filter chain and redacted security failures. */
    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            Environment environment,
            ObjectProvider<PortfolioRequestSecurity> portfolioSecurity)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health/" + "**",
                                "/actuator/info",
                                "/api/openapi/" + "**",
                                "/api/swagger-ui/" + "**",
                                "/api/v1/portfolio/session",
                                "/api/v1/portfolio/session/" + "**",
                                "/api/v1/demo/token")
                        .permitAll()
                        .requestMatchers(publicMetricsMatcher(environment))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        PortfolioRequestSecurity portfolio = portfolioSecurity.getIfAvailable();
        if (portfolio != null) {
            http.addFilterAfter(new PortfolioSessionBindingFilter(portfolio), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    /** Converts standard token roles and scopes into Spring Security authorities. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtAuthoritiesConverter());
        return converter;
    }

    /** Validates the configured production issuer, timestamps, and API audience. */
    @Bean
    @Profile("!demo & !test & !portfolio")
    JwtDecoder oidcJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            ResilientPaySecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(oidcValidator(issuer, properties.audience()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> oidcValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience));
    }

    static RequestMatcher publicMetricsMatcher(Environment environment) {
        boolean localDemo = environment.acceptsProfiles(Profiles.of("demo", "portfolio"));
        return request -> localDemo && "/actuator/prometheus".equals(request.getRequestURI());
    }

    /** Restricts browser calls to explicitly configured local UI origins and API headers. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(ResilientPayHttpProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Idempotency-Key", CorrelationIdFilter.HEADER_NAME));
        configuration.setExposedHeaders(
                List.of("Location", "Idempotency-Replayed", CorrelationIdFilter.HEADER_NAME, "Content-Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/" + "**", configuration);
        return source;
    }
}
