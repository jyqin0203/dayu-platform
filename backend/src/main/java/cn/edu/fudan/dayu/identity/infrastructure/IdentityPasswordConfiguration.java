package cn.edu.fudan.dayu.identity.infrastructure;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** New passwords use PBKDF2; migrated Argon2id hashes carry an explicit {argon2} prefix. */
@Configuration
public class IdentityPasswordConfiguration {
    @Bean public PasswordEncoder identityPasswordEncoder() {
        String id = "pbkdf2@SpringSecurity_v5_8";
        var bcrypt = new BCryptPasswordEncoder();
        var encoder = new DelegatingPasswordEncoder(id, Map.of(id,
                Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(), "bcrypt", bcrypt,
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }
}
