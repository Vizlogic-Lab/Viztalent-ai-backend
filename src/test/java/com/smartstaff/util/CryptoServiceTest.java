package com.smartstaff.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService("unit-test-encryption-key-0001");

    @Test
    @DisplayName("what you encrypt decrypts back to the same text (including non-ASCII)")
    void roundTrip() {
        for (String secret : new String[]{"AIzaSyExampleKey123", "tökén-with-ünïcode-✓", "x"}) {
            assertThat(crypto.decrypt(crypto.encrypt(secret))).isEqualTo(secret);
        }
    }

    @Test
    @DisplayName("the stored form doesn't contain the secret, and is different every time (random IV)")
    void ciphertextHidesPlaintextAndVaries() {
        String secret = "super-secret-twilio-token";
        String first = crypto.encrypt(secret);
        String second = crypto.encrypt(secret);

        assertThat(first).doesNotContain(secret).isNotEqualTo(second);
        assertThat(new String(Base64.getDecoder().decode(first))).doesNotContain(secret);
    }

    @Test
    @DisplayName("tampering with stored ciphertext is detected (GCM authentication), not silently decrypted")
    void tamperingDetected() {
        byte[] stored = Base64.getDecoder().decode(crypto.encrypt("value"));
        stored[stored.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(stored);

        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a different key cannot decrypt")
    void wrongKeyCannotDecrypt() {
        String stored = crypto.encrypt("value");
        CryptoService other = new CryptoService("a-completely-different-key-0002");

        assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null passes through untouched")
    void nullPassthrough() {
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("mask() shows enough to recognise a key without revealing it")
    void mask() {
        assertThat(CryptoService.mask(null)).isNull();
        assertThat(CryptoService.mask("  ")).isNull();
        assertThat(CryptoService.mask("short")).isEqualTo("*****");
        assertThat(CryptoService.mask("AIzaSyABCDEFGHIJKLMNOP7890")).isEqualTo("AIza…7890");
    }
}
