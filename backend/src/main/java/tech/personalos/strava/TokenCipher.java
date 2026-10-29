package tech.personalos.strava;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("strava-sync")
class TokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    TokenCipher(StravaSettings settings) {
        try {
            byte[] bytes = Base64.getDecoder().decode(settings.tokenEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new StravaFailure("INVALID_TOKEN_ENCRYPTION_KEY");
        }
    }

    String encrypt(String value) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (Exception e) { throw new StravaFailure("TOKEN_ENCRYPTION_FAILED"); }
    }
    
    String decrypt(String value) {
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(value));
            byte[] nonce = new byte[12];
            bytes.get(nonce);
            byte[] encrypted = new byte[bytes.remaining()];
            bytes.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new StravaFailure("TOKEN_DECRYPTION_FAILED"); }
    }
}