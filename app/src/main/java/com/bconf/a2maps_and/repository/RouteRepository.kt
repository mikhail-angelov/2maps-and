package com.bconf.a2maps_and.repository // New package

import android.util.Log
import com.bconf.a2maps_and.routing.RetrofitClient
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteRequest
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

class RouteRepository {
    private val valhallaService = RetrofitClient.instance // Your existing Retrofit service

    suspend fun getRoute(from: ValhallaLocation, to: ValhallaLocation, costing: String = "auto"): Result<ValhallaRouteResponse> {
        return withContext(Dispatchers.IO) { // Perform network call on IO dispatcher
            try {
                val request = ValhallaRouteRequest(locations = listOf(from, to), costing = costing)
                val response = valhallaService.getRoute(request = request) // Assuming default URL in service
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Unknown error from Valhalla"
                    Log.e("RouteRepository", "Error: ${response.code()} - $errorMsg")
                    Result.failure(Exception("Failed to get route: ${response.code()} - $errorMsg"))
                }
            } catch (e: Exception) {
                Log.e("RouteRepository", "Exception fetching route: ${e.message}", e)
                Result.failure(e)
            }
        }
    }
}
