package com.bconf.a2maps_and.routing

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // You need a base URL for Retrofit, even if you override it with @Url.
    // It can be a dummy one if all your calls use @Url.
    private const val BASE_URL = "http://185.231.246.34:8002/" // Or your actual base Valhalla URL

    val instance: ValhallaService by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // Log request and response bodies
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor) // Add for debugging
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // Add client with interceptor
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ValhallaService::class.java)
    }
}