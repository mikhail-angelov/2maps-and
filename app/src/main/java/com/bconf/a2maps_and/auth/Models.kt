package com.bconf.a2maps_and.auth

data class ServerPlacemark(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val rate: Int?,
    val description: String?,
    val timestamp: Long,
    val removed: Boolean? = false
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class SignUpRequest(
    val name: String,
    val email: String,
    val password: String
)

data class AuthResponse(
    val token: String,
    val user: UserPayload?
)

data class UserPayload(
    val id: String,
    val email: String,
    val role: String?
)

data class CheckResponse(
    val token: String,
    val user: UserPayload?
)
