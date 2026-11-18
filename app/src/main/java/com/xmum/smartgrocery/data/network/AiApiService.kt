package com.xmum.smartgrocery.data.network

import com.xmum.smartgrocery.data.network.dto.RecipeRequest
import com.xmum.smartgrocery.data.network.dto.RecipeResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApiService {
    @POST("recipes/generate")
    suspend fun generateRecipes(@Body request: RecipeRequest): RecipeResponse
}
