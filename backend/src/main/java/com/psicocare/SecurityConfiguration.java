package com.psicocare;

import com.psicocare.shared.Json;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()) // API uses bearer; cookie/CSRF terminated by
                // the BFF.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/actuator/health/**",
                                                "/actuator/prometheus",
                                                "/v1/public/**",
                                                "/v1/webhooks/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> {})
                                        .authenticationEntryPoint(
                                                (req, res, error) -> {
                                                    res.setStatus(401);
                                                    res.setContentType("application/problem+json");
                                                    res.getWriter()
                                                            .write(
                                                                    Json.object(
                                                                                    "type",
                                                                                    "about:blank",
                                                                                    "title",
                                                                                    "Autenticação"
                                                                                        + " necessária",
                                                                                    "status",
                                                                                    401,
                                                                                    "code",
                                                                                    "UNAUTHENTICATED",
                                                                                    "requestId",
                                                                                    java.util.UUID
                                                                                            .randomUUID())
                                                                            .toString());
                                                }))
                .headers(h -> h.contentTypeOptions(c -> {}).frameOptions(f -> f.deny()))
                .build();
    }

    @Bean
    JwtDecoder decoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${app.oidc-audience}") String audience) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        jwt ->
                                jwt.getAudience().contains(audience)
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                                new OAuth2Error("invalid_token"))));
        return decoder;
    }
}
