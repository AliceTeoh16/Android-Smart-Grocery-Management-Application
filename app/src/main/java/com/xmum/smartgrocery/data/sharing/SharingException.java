package com.xmum.smartgrocery.data.sharing;

import androidx.annotation.NonNull;

import java.io.IOException;

public final class SharingException extends IOException {
    private final int status;
    private final String code;

    public SharingException(int status, @NonNull String code, @NonNull String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    @NonNull
    public String getCode() {
        return code;
    }
}
