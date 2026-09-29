package com.aakashmunawar.studygroups.security;

import com.aakashmunawar.studygroups.domain.Student;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues access tokens. The subject is the student's id. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder, @Value("${app.jwt.ttl-hours}") long ttlHours) {
        this.encoder = encoder;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String issue(Student student) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("study-group-matcher")
                .subject(String.valueOf(student.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("name", student.getDisplayName())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public static long studentId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
