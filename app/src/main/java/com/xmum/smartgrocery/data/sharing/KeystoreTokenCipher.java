package com.xmum.smartgrocery.data.sharing;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Protects tokens with a non-exportable Android Keystore key. */
public final class KeystoreTokenCipher implements TokenCipher {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "smart-grocery-household-sharing-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        Key stored = store.getKey(ALIAS, null);
        if (stored instanceof SecretKey) {
            return (SecretKey) stored;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    @NonNull
    @Override
    public synchronized String encrypt(@NonNull String token) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] record = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, record, iv.length, ciphertext.length);
        return Base64.encodeToString(record, Base64.NO_WRAP);
    }

    @NonNull
    @Override
    public synchronized String decrypt(@NonNull String encrypted) throws GeneralSecurityException, IOException {
        byte[] bytes = Base64.decode(encrypted, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
        return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
    }
}
