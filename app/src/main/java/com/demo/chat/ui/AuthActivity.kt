package com.demo.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.demo.chat.ChatApplication
import com.demo.chat.R
import com.demo.chat.databinding.ActivityAuthBinding
import com.demo.chat.sdk.core.ChatClient
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var chatClient: ChatClient
    private var isRegisterMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        chatClient = (application as ChatApplication).chatClient

        // If already logged in, skip directly to MainActivity
        if (chatClient.isLoggedIn()) {
            startMainActivity()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            binding.authScrollView.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )
            windowInsets
        }

        setupServerUrlConfig()
        setupAuthToggle()
        setupQuickDemoButtons()
        setupSubmitButton()
    }

    private fun setupServerUrlConfig() {
        binding.etServerUrl.setText(chatClient.session.getBaseUrl())
        binding.btnSaveServerUrl.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                chatClient.session.setBaseUrl(url)
                Toast.makeText(this, "API Server URL set to: $url", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAuthToggle() {
        binding.toggleAuthMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            when (checkedId) {
                R.id.tabLogin -> {
                    isRegisterMode = false
                    binding.tilName.visibility = View.GONE
                    binding.btnSubmitAuth.text = "Login to Chat"
                    binding.tvErrorMessage.visibility = View.GONE
                }
                R.id.tabRegister -> {
                    isRegisterMode = true
                    binding.tilName.visibility = View.VISIBLE
                    binding.btnSubmitAuth.text = "Register & Start Chatting"
                    binding.tvErrorMessage.visibility = View.GONE
                }
            }
        }
    }

    private fun setupQuickDemoButtons() {
        binding.btnQuickUser1.setOnClickListener {
            binding.etEmail.setText("alice2@example.com")
            binding.etPassword.setText("password123")
            if (isRegisterMode) {
                binding.etName.setText("Alice Johnson")
            }
        }

        binding.btnQuickUser2.setOnClickListener {
            binding.etEmail.setText("bob@example.com")
            binding.etPassword.setText("password123")
            if (isRegisterMode) {
                binding.etName.setText("Bob Smith")
            }
        }
    }

    private fun setupSubmitButton() {
        binding.btnSubmitAuth.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            // Update server URL if changed
            val customUrl = binding.etServerUrl.text.toString().trim()
            if (customUrl.isNotEmpty()) {
                chatClient.session.setBaseUrl(customUrl)
            }

            if (email.isEmpty()) {
                showError("Please enter your email address")
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                showError("Please enter your password")
                return@setOnClickListener
            }

            if (isRegisterMode) {
                val name = binding.etName.text.toString().trim()
                if (name.isEmpty()) {
                    showError("Please enter your full name")
                    return@setOnClickListener
                }
                performRegister(name, email, password)
            } else {
                performLogin(email, password)
            }
        }
    }

    private fun performLogin(email: String, password: String) {
        setLoading(true)
        hideError()

        lifecycleScope.launch {
            val result = chatClient.login(email, password)
            setLoading(false)

            result.onSuccess { authData ->
                Toast.makeText(
                    this@AuthActivity,
                    "Welcome back, ${authData.user?.name ?: "User"}!",
                    Toast.LENGTH_SHORT
                ).show()
                startMainActivity()
            }.onFailure { err ->
                showError("Login failed: ${err.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    private fun performRegister(name: String, email: String, password: String) {
        setLoading(true)
        hideError()

        lifecycleScope.launch {
            val result = chatClient.register(name, email, password)
            setLoading(false)

            result.onSuccess { authData ->
                Toast.makeText(
                    this@AuthActivity,
                    "Account created successfully for ${authData.user?.name ?: name}!",
                    Toast.LENGTH_SHORT
                ).show()
                startMainActivity()
            }.onFailure { err ->
                showError("Registration failed: ${err.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressAuth.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmitAuth.isEnabled = !loading
    }

    private fun showError(msg: String) {
        binding.tvErrorMessage.text = msg
        binding.tvErrorMessage.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvErrorMessage.visibility = View.GONE
    }
}
