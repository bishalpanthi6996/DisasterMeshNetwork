package com.example.disastermesh

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

data class DisasterFeed(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double
)

interface ApiService {
    @POST("sync/messages")
    suspend fun syncMessage(@Body message: MessageEntity): Response<Unit>

    @GET("disaster/feeds")
    suspend fun getActiveDisasters(): Response<List<DisasterFeed>>

    @GET("sos/nearby")
    suspend fun getNearbySos(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radiusKm") radiusKm: Double
    ): Response<List<MessageEntity>>

    @POST("user/status")
    suspend fun reportUserStatus(
        @Query("userId") userId: String,
        @Query("isSafe") isSafe: Boolean,
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): Response<Unit>
}
