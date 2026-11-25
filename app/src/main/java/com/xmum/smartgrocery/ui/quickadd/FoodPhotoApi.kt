package com.xmum.smartgrocery.ui.quickadd

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

internal data class VisualFoodItem(val name: String, val category: String?, val quantity: Int?, val subcategory: String? = null)
internal data class FoodPhotoRequest(@SerializedName("image_base64") val image: String, @SerializedName("mime_type") val mime: String = "image/jpeg")
internal data class FoodPhotoResponse(val items: List<VisualFoodItem>?)
internal interface FoodPhotoApi {
    @POST("ingredients/scan") suspend fun recognize(@Body request: FoodPhotoRequest): FoodPhotoResponse
}
