package com.xmum.smartgrocery.ui.quickadd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.google.gson.annotations.SerializedName
import com.xmum.smartgrocery.BuildConfig
import com.xmum.smartgrocery.utils.GroceryCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal data class VisualFoodItem(val name: String, val category: String?, val quantity: Int?, val subcategory: String? = null)
internal data class FoodPhotoRequest(@SerializedName("image_base64") val image: String, @SerializedName("mime_type") val mime: String = "image/jpeg")
internal data class FoodPhotoResponse(val items: List<VisualFoodItem>?)
internal interface FoodPhotoApi {
    @POST("ingredients/scan") suspend fun recognize(@Body request: FoodPhotoRequest): FoodPhotoResponse
}

/** Visual multimodal recognition through the existing server; no OCR text is used as an item source. */
internal class VisualFoodRecognition(private val context: Context) {
    private val api by lazy {
        Retrofit.Builder().baseUrl(BuildConfig.AI_BASE_URL)
            .client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(50, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(FoodPhotoApi::class.java)
    }

    suspend fun recognize(uri: Uri): List<VisualFoodItem> {
        val photo = withContext(Dispatchers.IO) { preparePhoto(context, uri) }
        return api.recognize(photo).items.orEmpty().take(20).mapNotNull { item ->
            val name = item.name?.trim()?.take(100).orEmpty()
            if (name.isBlank()) null else VisualFoodItem(name,
                GroceryCategories.recognizedSelection(name, item.category, item.subcategory), item.quantity?.takeIf { it in 1..999 })
        }
    }

    companion object {
        internal fun preparePhoto(context: Context, uri: Uri): FoodPhotoRequest {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Please choose a readable item photo.")
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
            }
            var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalArgumentException("Couldn’t open this photo. Choose it again.")
            try {
                val orientation = resolver.openInputStream(uri)?.use {
                    try { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    catch (_: java.io.IOException) { ExifInterface.ORIENTATION_NORMAL }
                } ?: ExifInterface.ORIENTATION_NORMAL
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                if (!matrix.isIdentity) {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated !== bitmap) bitmap.recycle()
                    bitmap = rotated
                }
                val bytes = ByteArrayOutputStream().use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IllegalArgumentException("Couldn’t prepare this photo.")
                    it.toByteArray()
                }
                require(bytes.size <= 4 * 1024 * 1024) { "This photo is too large. Try a smaller image." }
                return FoodPhotoRequest(Base64.encodeToString(bytes, Base64.NO_WRAP))
            } finally { bitmap.recycle() }
        }
    }
}
