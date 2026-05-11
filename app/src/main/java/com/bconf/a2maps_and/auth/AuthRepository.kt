package com.bconf.a2maps_and.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object AuthRepository {

    private const val TAG = "AuthRepository"
    private const val PREFS_NAME = "auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"

    // Change this to your server URL
    private const val BASE_URL = "https://2maps.xyz/"

    private lateinit var prefs: SharedPreferences
    private var cachedToken: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    // Attach Bearer token if available
                    .apply {
                        cachedToken?.let { header("Authorization", "Bearer $it") }
                    }
                    .build()
                val response = chain.proceed(request)

                // Detect Cloudflare HTML challenge masquerading as 200
                if (response.code == 200) {
                    val contentType = response.header("Content-Type") ?: ""
                    if ("text/html" in contentType) {
                        Log.w(TAG, "Cloudflare challenge detected! Got HTML instead of JSON.")
                    }
                }
                response
            }
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    val api: MapsApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MapsApi::class.java)
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cachedToken = prefs.getString(KEY_TOKEN, null)
    }

    fun getToken(): String? = cachedToken

    fun isLoggedIn(): Boolean = cachedToken != null

    fun saveAuth(token: String, userId: String?, email: String?) {
        cachedToken = token
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()
        Log.d(TAG, "Auth saved for $email")
    }

    fun clearAuth() {
        cachedToken = null
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .apply()
        Log.d(TAG, "Auth cleared")
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)
}
