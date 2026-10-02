package cn.edu.fudan.dayu.identity.infrastructure;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** Versioned standard-library password encoding; new Unicode passwords support the full API length. */
@Configuration
public class IdentityPasswordConfiguration {
    @Bean public PasswordEncoder identityPasswordEncoder() {
        String id = "pbkdf2@SpringSecurity_v5_8";
        var bcrypt = new BCryptPasswordEncoder();
        var encoder = new DelegatingPasswordEncoder(id, Map.of(id,
                Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(), "bcrypt", bcrypt));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }
}
