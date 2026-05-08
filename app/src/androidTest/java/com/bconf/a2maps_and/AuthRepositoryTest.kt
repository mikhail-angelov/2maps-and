package com.bconf.a2maps_and

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bconf.a2maps_and.auth.AuthRepository
import com.bconf.a2maps_and.auth.LoginRequest
import com.bconf.a2maps_and.auth.SignUpRequest
import com.bconf.a2maps_and.auth.ServerPlacemark
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AuthRepository.init(context)
        AuthRepository.clearAuth()
    }

    @Test
    fun `init should not crash`() {
        assertNotNull(context)
    }

    @Test
    fun `isLoggedIn returns false when no token`() {
        assertFalse(AuthRepository.isLoggedIn())
    }

    @Test
    fun `save and retrieve auth token`() {
        AuthRepository.saveAuth("test-token-123", "user-1", "test@test.com")
        assertTrue(AuthRepository.isLoggedIn())
        assertEquals("test-token-123", AuthRepository.getToken())
        assertEquals("user-1", AuthRepository.getUserId())
        assertEquals("test@test.com", AuthRepository.getUserEmail())
    }

    @Test
    fun `clearAuth removes all stored data`() {
        AuthRepository.saveAuth("token", "uid", "e@e.com")
        AuthRepository.clearAuth()
        assertFalse(AuthRepository.isLoggedIn())
        assertNull(AuthRepository.getToken())
        assertNull(AuthRepository.getUserId())
        assertNull(AuthRepository.getUserEmail())
    }

    @Test
    fun `login request serialization`() {
        val request = LoginRequest("user@test.com", "pass123")
        val json = Gson().toJson(request)
        assertTrue(json.contains("user@test.com"))
        assertTrue(json.contains("pass123"))
    }

    @Test
    fun `signup request serialization`() {
        val request = SignUpRequest("Test User", "user@test.com", "pass123")
        val json = Gson().toJson(request)
        assertTrue(json.contains("Test User"))
        assertTrue(json.contains("user@test.com"))
    }

    @Test
    fun `server placemark can be serialized`() {
        val mark = ServerPlacemark(
            id = "abc-123",
            name = "Test",
            lat = 56.32,
            lng = 43.98,
            rate = 5,
            description = "desc",
            timestamp = 1000000L
        )
        val json = Gson().toJson(mark)
        assertTrue(json.contains("abc-123"))
        assertTrue(json.contains("\"lat\":56.32"))
    }
}
