package com.xmum.smartgrocery.data.session;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    public static final int ITERATIONS = 600_000;
    private static final String HEX_DIGITS = "0123456789abcdef";

    private PasswordHasher() {
    }

    @NonNull
    public static String salt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return toHex(salt);
    }

    @NonNull
    public static String hash(@NonNull String password, @NonNull String salt)
            throws GeneralSecurityException {
        return hash(password, salt, ITERATIONS);
    }

    @NonNull
    public static String hash(@NonNull String password, @NonNull String salt, int iterations)
            throws GeneralSecurityException {
        if (iterations < ITERATIONS) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), fromHex(salt), iterations, 256);
        try {
            return toHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean verify(@NonNull String password, @NonNull String salt, int iterations,
            @NonNull String expected) throws GeneralSecurityException {
        return !expected.isEmpty()
                && MessageDigest.isEqual(fromHex(hash(password, salt, iterations)), fromHex(expected));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(HEX_DIGITS.charAt((value & 0xff) >>> 4));
            result.append(HEX_DIGITS.charAt(value & 0x0f));
        }
        return result.toString();
    }

    private static byte[] fromHex(String value) {
        byte[] bytes = new byte[(value.length() + 1) / 2];
        for (int i = 0; i < bytes.length; i++) {
            int start = i * 2;
            bytes[i] = (byte) Integer.parseInt(value.substring(start, Math.min(start + 2, value.length())), 16);
        }
        return bytes;
    }
}
