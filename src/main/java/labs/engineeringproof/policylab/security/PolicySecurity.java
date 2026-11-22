package labs.engineeringproof.policylab.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PolicySecurity {
    public static List<String> groups(Jwt jwt) {
        Object claim = jwt.getClaims().get("groups");
        if (!(claim instanceof List<?> groups)) {
            return List.of();
        }
        return groups.stream().filter(String.class::isInstance).map(String.class::cast).distinct().toList();
    }

    @Bean
    JwtDecoder policyJwtDecoder(@Value("${lab.auth.issuer}") String issuer,
                                @Value("${lab.auth.jwk-set-uri}") String jwks,
                                @Value("${lab.auth.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<>("exp", expiration -> expiration != null),
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience))));
        return decoder;
    }

    @Bean
    SecurityFilterChain policyRoutes(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.POST, "/questions").authenticated()
                        .requestMatchers(HttpMethod.GET, "/sources/*").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/policies/*/active-revision")
                        .access((auth, context) -> new AuthorizationDecision(
                                auth.get().getPrincipal() instanceof Jwt jwt && groups(jwt).contains("policy-publisher")))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(withDefaults()))
                .build();
    }
}
