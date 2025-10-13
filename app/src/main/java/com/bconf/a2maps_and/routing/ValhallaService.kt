package com.bconf.a2maps_and.routing

import retrofit2.Response // Import Retrofit's Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url // To allow dynamic URLs if needed, or set base URL in Retrofit builder

interface ValhallaService {
    @POST("route")
    suspend fun getRoute(
        @Body request: ValhallaRouteRequest
    ): Response<ValhallaRouteResponse> // Use Retrofit's Response for error handling
}
