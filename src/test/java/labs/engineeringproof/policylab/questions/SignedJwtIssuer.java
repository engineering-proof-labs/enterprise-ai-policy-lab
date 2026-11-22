package labs.engineeringproof.policylab.questions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import static java.nio.charset.StandardCharsets.UTF_8;

final class SignedJwtIssuer implements AutoCloseable {
    private final HttpServer server;
    private final RSAKey key;
    private final byte[] jwks;

    SignedJwtIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("policy-test").generate();
            jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    exchange.getResponseBody().write(jwks);
                }
            });
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException("Failed to start the test issuer", exception);
        }
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String jwksUri() {
        return issuer() + "/jwks";
    }

    String token(String... groups) throws JOSEException {
        return customToken(issuer(), "policy-api", Instant.now().plusSeconds(600), groups);
    }

    String customToken(String issuer, String audience, Instant expiration, String... groups) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("fixture-client").audience(audience)
                .expirationTime(expiration == null ? null : Date.from(expiration)).claim("groups", List.of(groups)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
