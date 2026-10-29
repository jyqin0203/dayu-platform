package cn.edu.fudan.dayu.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class IdentityPasswordConfigurationTest {
    private final org.springframework.security.crypto.password.PasswordEncoder encoder =
            new IdentityPasswordConfiguration().identityPasswordEncoder();

    @Test void verifiesPhpArgon2idWithoutChangingTheLegacyHash() {
        // Synthetic PHP password_hash fixture; no real account credentials.
        String hash = "{argon2}$argon2id$v=19$m=65536,t=4,p=1$RW1uVmlnYzR6eVRzVG1BTA$s6CiCfdCkgTtEsZ2rOIhOKO4nP0c24yqxseafpv+a/k";
        assertThat(encoder.matches("migration-fixture-only", hash)).isTrue();
        assertThat(encoder.matches("incorrect", hash)).isFalse();
    }

    @Test void retainsNewPasswordAndLegacyBcryptSupport() {
        String hash = encoder.encode("新的密码-test");
        assertThat(hash).startsWith("{pbkdf2@SpringSecurity_v5_8}");
        assertThat(encoder.matches("新的密码-test", hash)).isTrue();
        assertThat(encoder.matches("incorrect", hash)).isFalse();
        String bcrypt = new BCryptPasswordEncoder().encode("legacy-fixture-only");
        assertThat(encoder.matches("legacy-fixture-only", bcrypt)).isTrue();
        assertThat(encoder.matches("legacy-fixture-only", "{bcrypt}" + bcrypt)).isTrue();
    }
}
