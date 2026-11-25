package com.xmum.smartgrocery.ui.quickadd;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.xmum.smartgrocery.BuildConfig;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.GroceryCategories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Prepares photos and interprets the server's visual ingredient suggestions. */
final class VisualFoodRecognition {
    private final Context context;
    private final Lazy<FoodPhotoApi> api = LazyKt.lazy(() -> new Retrofit.Builder()
            .baseUrl(BuildConfig.AI_BASE_URL)
            .client(new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(50, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(FoodPhotoApi.class));

    VisualFoodRecognition(@NonNull Context context) {
        this.context = context;
    }

    @NonNull
    Object recognize(@NonNull Uri uri, @NonNull Continuation<? super List<VisualFoodItem>> completion) {
        return AndroidCoroutines.<FoodPhotoRequest, List<VisualFoodItem>>then(
                next -> AndroidCoroutines.onIo(() -> preparePhoto(context, uri), next),
                (photo, next) -> AndroidCoroutines.<FoodPhotoResponse, List<VisualFoodItem>>then(
                        response -> api.getValue().recognize(photo, response),
                        (response, unused) -> suggestions(response.getItems()), next),
                completion);
    }

    private static List<VisualFoodItem> suggestions(List<VisualFoodItem> response) {
        if (response == null || response.isEmpty()) {
            return Collections.emptyList();
        }
        List<VisualFoodItem> items = new ArrayList<>();
        for (VisualFoodItem item : response.subList(0, Math.min(response.size(), 20))) {
            String rawName = item.getName();
            String name = rawName == null ? "" : StringsKt.trim(rawName).toString();
            name = name.substring(0, Math.min(name.length(), 100));
            if (!StringsKt.isBlank(name)) {
                Integer quantity = item.getQuantity();
                if (quantity != null && (quantity < 1 || quantity > 999)) {
                    quantity = null;
                }
                items.add(new VisualFoodItem(name,
                        GroceryCategories.recognizedSelection(name, item.getCategory(), item.getSubcategory()),
                        quantity, null));
            }
        }
        return items;
    }

    @NonNull
    static FoodPhotoRequest preparePhoto(@NonNull Context context, @NonNull Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream != null) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("Please choose a readable item photo.");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) {
            options.inSampleSize *= 2;
        }

        Bitmap bitmap;
        try (InputStream stream = resolver.openInputStream(uri)) {
            bitmap = stream == null ? null : BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("Couldn’t open this photo. Choose it again.");
        }
        try {
            Matrix matrix = orientationMatrix(orientation(resolver, uri));
            if (!matrix.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                }
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, encoded)) {
                throw new IllegalArgumentException("Couldn’t prepare this photo.");
            }
            byte[] bytes = encoded.toByteArray();
            if (bytes.length > 4 * 1024 * 1024) {
                throw new IllegalArgumentException("This photo is too large. Try a smaller image.");
            }
            return new FoodPhotoRequest(Base64.encodeToString(bytes, Base64.NO_WRAP), "image/jpeg");
        } finally {
            bitmap.recycle();
        }
    }

    private static int orientation(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream != null) {
                try {
                    return new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                } catch (IOException ignored) {
                    return ExifInterface.ORIENTATION_NORMAL;
                }
            }
        }
        return ExifInterface.ORIENTATION_NORMAL;
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                break;
        }
        return matrix;
    }
}
