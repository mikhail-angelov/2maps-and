package com.bconf.a2maps_and.routing

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.bconf.a2maps_and.BuildConfig

object RetrofitClient {

    val instance: ValhallaService by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // Log request and response bodies
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor) // Add for debugging
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.VALHALLA_BASE_URL)
            .client(okHttpClient) // Add client with interceptor
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ValhallaService::class.java)
    }
}