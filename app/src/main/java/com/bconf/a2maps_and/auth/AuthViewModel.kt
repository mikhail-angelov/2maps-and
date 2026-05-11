package com.bconf.a2maps_and.auth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val email: String? = null,
    val error: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(
        AuthUiState(
            isLoggedIn = AuthRepository.isLoggedIn(),
            email = AuthRepository.getUserEmail()
        )
    )
    val uiState: LiveData<AuthUiState> = _uiState

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isLoading = true, error = null)
            try {
                val response = AuthRepository.api.login(LoginRequest(email, password))
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        AuthRepository.saveAuth(body.token, body.user?.id, body.user?.email)
                        _uiState.value = AuthUiState(
                            isLoggedIn = true,
                            email = body.user?.email
                        )
                    } else {
                        _uiState.value = _uiState.value?.copy(
                            isLoading = false,
                            error = "Empty response from server"
                        )
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Login failed"
                    _uiState.value = _uiState.value?.copy(
                        isLoading = false,
                        error = errorBody
                    )
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Login error", e)
                _uiState.value = _uiState.value?.copy(
                    isLoading = false,
                    error = "Network error: ${e.localizedMessage}"
                )
            }
        }
    }

    fun signUp(name: String, email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isLoading = true, error = null)
            try {
                val response = AuthRepository.api.signUp(SignUpRequest(name, email, password))
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        AuthRepository.saveAuth(body.token, body.user?.id, body.user?.email)
                        _uiState.value = AuthUiState(
                            isLoggedIn = true,
                            email = body.user?.email
                        )
                    } else {
                        _uiState.value = _uiState.value?.copy(
                            isLoading = false,
                            error = "Empty response from server"
                        )
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Sign up failed"
                    _uiState.value = _uiState.value?.copy(
                        isLoading = false,
                        error = errorBody
                    )
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "SignUp error", e)
                _uiState.value = _uiState.value?.copy(
                    isLoading = false,
                    error = "Network error: ${e.localizedMessage}"
                )
            }
        }
    }

    fun logout() {
        AuthRepository.clearAuth()
        _uiState.value = AuthUiState()
    }
}
