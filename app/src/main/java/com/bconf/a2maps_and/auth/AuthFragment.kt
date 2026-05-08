package com.bconf.a2maps_and.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.databinding.FragmentAuthBinding

class AuthFragment : Fragment() {

    private var _binding: FragmentAuthBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.loginButton.setOnClickListener {
            val email = binding.emailEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()
            if (email.isNotEmpty() && password.isNotEmpty()) {
                authViewModel.login(email, password)
            } else {
                binding.errorText.text = "Please fill in email and password"
                binding.errorText.visibility = View.VISIBLE
            }
        }

        binding.signUpButton.setOnClickListener {
            val name = binding.nameEditText.text.toString().trim()
            val email = binding.emailEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()
            if (name.isNotEmpty() && email.isNotEmpty() && password.isNotEmpty()) {
                authViewModel.signUp(name, email, password)
            } else {
                binding.errorText.text = "Please fill in all fields"
                binding.errorText.visibility = View.VISIBLE
            }
        }

        authViewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.loadingSpinner.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            if (state.error != null) {
                binding.errorText.text = state.error
                binding.errorText.visibility = View.VISIBLE
            } else {
                binding.errorText.visibility = View.GONE
            }
            if (state.isLoggedIn) {
                // Navigate back
                parentFragmentManager.popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
