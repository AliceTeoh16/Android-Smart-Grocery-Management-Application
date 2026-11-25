package com.xmum.smartgrocery.data.network;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class AiRecipeClient {
    private AiRecipeClient() {
    }

    @NonNull
    public static AiApiService create(@NonNull String baseUrl) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AiApiService.class);
    }
}
