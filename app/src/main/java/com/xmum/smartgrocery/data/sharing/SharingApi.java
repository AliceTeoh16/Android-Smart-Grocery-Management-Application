package com.xmum.smartgrocery.data.sharing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import kotlin.text.StringsKt;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class SharingApi {
    private static final MediaType JSON = MediaType.parse("application/json");
    private final String baseUrl;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    public SharingApi(@NonNull String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @NonNull
    public String getBaseUrl() {
        return baseUrl;
    }

    @WorkerThread
    @NonNull
    public RemoteAuth register(@NonNull String name, @NonNull String email, @NonNull String password) throws IOException {
        return request("v1/auth/register", null, fields("name", name, "email", email, "password", password),
                RemoteAuth.class);
    }

    @WorkerThread
    @NonNull
    public RemoteAuth login(@NonNull String email, @NonNull String password) throws IOException {
        return request("v1/auth/login", null, fields("email", email, "password", password), RemoteAuth.class);
    }

    @WorkerThread
    @NonNull
    public RemoteHomes homes(@NonNull String token) throws IOException {
        return request("v1/households", token, null, RemoteHomes.class);
    }

    @WorkerThread
    @NonNull
    public RemoteSnapshot create(@NonNull String token, @NonNull String name, @NonNull String description)
            throws IOException {
        return request("v1/households", token, fields("name", name, "description", description), RemoteSnapshot.class);
    }

    @WorkerThread
    @NonNull
    public RemoteSnapshot join(@NonNull String token, @NonNull String code) throws IOException {
        return request("v1/households/join", token, Collections.singletonMap("code", code), RemoteSnapshot.class);
    }

    @WorkerThread
    @NonNull
    public RemoteSnapshot sync(@NonNull String token, @NonNull String householdId,
            @NonNull List<RemoteMutation> operations) throws IOException {
        return request("v1/households/" + householdId + "/sync", token,
                Collections.singletonMap("operations", operations), RemoteSnapshot.class);
    }

    @WorkerThread
    @NonNull
    public RemoteSnapshot invite(@NonNull String token, @NonNull String householdId, @NonNull String email)
            throws IOException {
        return request("v1/households/" + householdId + "/invitations", token,
                Collections.singletonMap("email", email), RemoteSnapshot.class);
    }

    @WorkerThread
    @NonNull
    public JsonObject leave(@NonNull String token, @NonNull String householdId) throws IOException {
        return request("v1/households/" + householdId + "/leave", token, Collections.emptyMap(), JsonObject.class);
    }

    @WorkerThread
    @NonNull
    public JsonObject logout(@NonNull String token) throws IOException {
        return request("v1/auth/logout", token, Collections.emptyMap(), JsonObject.class);
    }

    private <T> T request(String path, @Nullable String token, @Nullable Object body, Class<T> type)
            throws IOException {
        Request.Builder request = new Request.Builder().url(baseUrl + path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.post(RequestBody.create(JSON, gson.toJson(body)));
        }
        try (Response response = client.newCall(request.build()).execute()) {
            String content = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                JsonObject error = parseError(content);
                throw new SharingException(response.code(), errorField(error, "code", "SERVER_ERROR"),
                        errorField(error, "message", "The sharing server could not complete the request."));
            }
            T value = gson.fromJson(content, type);
            if (value == null) {
                throw new IOException("Invalid response from the sharing server.");
            }
            return value;
        }
    }

    private JsonObject parseError(String content) {
        try {
            return gson.fromJson(content, JsonObject.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String errorField(JsonObject error, String field, String fallback) {
        JsonElement value = error == null ? null : error.get(field);
        return value != null ? value.getAsString() : fallback;
    }

    private static Map<String, String> fields(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    @NonNull
    public static String normalizeUrl(@NonNull String value) {
        String input = StringsKt.trim(value).toString();
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Enter a valid sharing server address.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean local = "localhost".equals(host) || privateIpv4(host) || host.endsWith(".local") || host.endsWith(".lan");
        int port = uri.getPort();
        String path = uri.getPath();
        String scheme = uri.getScheme();
        if (host.isEmpty() || port < -1 || port > 65535 || port == 0 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))
                || (!"https".equals(scheme) && !("http".equals(scheme) && local))) {
            throw new IllegalArgumentException("Use HTTPS, or an HTTP address on your local network, without a path or password.");
        }
        int end = input.length();
        while (end > 0 && input.charAt(end - 1) == '/') {
            end--;
        }
        return input.substring(0, end) + "/";
    }

    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] address = new int[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            Integer octet = StringsKt.toIntOrNull(part);
            if (octet == null || octet < 0 || octet > 255 || (part.length() > 1 && part.charAt(0) == '0')) {
                return false;
            }
            address[i] = octet;
        }
        return address[0] == 10 || address[0] == 127 || (address[0] == 192 && address[1] == 168)
                || (address[0] == 172 && address[1] >= 16 && address[1] <= 31);
    }
}
