package com.xmum.smartgrocery.data.sharing;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.security.GeneralSecurityException;

public interface TokenCipher {
    @NonNull
    String encrypt(@NonNull String token) throws GeneralSecurityException, IOException;

    @NonNull
    String decrypt(@NonNull String encrypted) throws GeneralSecurityException, IOException;
}
