package com.bconf.a2maps_and.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MapsApi {

    @POST("/auth/m/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("/auth/m/sign-up")
    suspend fun signUp(@Body request: SignUpRequest): Response<AuthResponse>

    @POST("/auth/m/check")
    suspend fun checkToken(
        @Header("authorization") token: String
    ): Response<CheckResponse>

    @POST("/marks/m/sync")
    suspend fun syncMarks(
        @Header("authorization") token: String,
        @Body marks: List<ServerPlacemark>
    ): Response<List<ServerPlacemark>>
}
