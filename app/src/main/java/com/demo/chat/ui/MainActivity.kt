package com.demo.chat.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.demo.chat.ChatApplication
import com.demo.chat.R
import com.demo.chat.data.model.ConnectionState
import com.demo.chat.data.model.User
import com.demo.chat.databinding.ActivityMainBinding
import com.demo.chat.databinding.DialogContactsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var messageAdapter: MessageAdapter

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedPhoto(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val chatClient = (application as ChatApplication).chatClient

        // Verify session: If not logged in, redirect to AuthActivity
        if (!chatClient.isLoggedIn()) {
            redirectToAuth()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = MainViewModel(chatClient)

        setupWindowInsets()
        setupRecyclerView(chatClient.session.getUserId())
        setupHeaderUserInfo()
        setupListeners()
        observeViewModel()

        // Discover active conversation and auto-connect WebSocket
        viewModel.loadInitialConversation()
    }

    /**
     * Handle edge-to-edge IME (keyboard) and system insets so the message input window
     * is NEVER overridden by the keyboard or screen orientation changes.
     */
    private fun setupWindowInsets() {
        val initialTopPadding = binding.topAppBar.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply status bar top padding to top app bar so green banner extends through status bar
            binding.topAppBar.setPadding(
                binding.topAppBar.paddingLeft,
                bars.top + initialTopPadding,
                binding.topAppBar.paddingRight,
                binding.topAppBar.paddingBottom
            )

            // Pad root layout at bottom with bars.bottom (nav bar or soft keyboard)
            // This cleanly lifts the entire inputBarContainer directly above the keyboard
            binding.root.setPadding(
                bars.left,
                0,
                bars.right,
                bars.bottom
            )

            if (imeInsets.bottom > 0 && ::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                binding.recyclerViewMessages.post {
                    binding.recyclerViewMessages.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }

            windowInsets
        }

        // Keep messages scrolled to bottom when keyboard or orientation resizes the layout
        binding.recyclerViewMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && ::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                binding.recyclerViewMessages.post {
                    binding.recyclerViewMessages.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }
        }
    }

    private fun setupHeaderUserInfo() {
        val user = viewModel.currentUser
        binding.tvCurrentUser.text = if (user != null) {
            "Logged in: ${user.name} (${user.email})"
        } else {
            "Logged in"
        }
    }

    private fun setupRecyclerView(currentUserId: Int) {
        messageAdapter = MessageAdapter(currentUserId)
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    private fun setupListeners() {
        binding.etMessageInput.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        binding.etMessageInput.setOnClickListener {
            if (::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                binding.recyclerViewMessages.postDelayed({
                    binding.recyclerViewMessages.scrollToPosition(messageAdapter.itemCount - 1)
                }, 150)
            }
        }

        binding.btnSendMessage.setOnClickListener {
            val text = binding.etMessageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val chatId = viewModel.activeChatId.value
                viewModel.sendTextMessage(chatId, text)
                binding.etMessageInput.text.clear()
            }
        }

        binding.btnAttachPhoto.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }

        binding.btnSelectContact.setOnClickListener {
            showContactsBottomSheet()
        }

        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            redirectToAuth()
        }
    }

    private fun showContactsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogContactsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val contactAdapter = ContactAdapter { selectedUser ->
            dialog.dismiss()
            viewModel.startChatWithUser(selectedUser)
        }

        dialogBinding.rvContacts.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
        }

        dialogBinding.progressContacts.visibility = View.VISIBLE
        dialogBinding.tvEmptyContacts.visibility = View.GONE

        // Trigger fetch
        viewModel.fetchRegisteredUsers()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.registeredUsers.collectLatest { users ->
                    dialogBinding.progressContacts.visibility = View.GONE
                    if (users.isEmpty()) {
                        dialogBinding.tvEmptyContacts.visibility = View.VISIBLE
                    } else {
                        dialogBinding.tvEmptyContacts.visibility = View.GONE
                        contactAdapter.submitList(users)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. Observe connection state StateFlow
                launch {
                    viewModel.connectionState.collectLatest { state ->
                        updateConnectionUi(state)
                    }
                }

                // 2. Observe message list StateFlow
                launch {
                    viewModel.messageList.collectLatest { messages ->
                        messageAdapter.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }

                // 3. Observe active recipient
                launch {
                    viewModel.currentRecipient.collectLatest { recipient ->
                        if (recipient != null) {
                            binding.tvChatPartnerName.text = "Chat: ${recipient.name}"
                        } else {
                            binding.tvChatPartnerName.text = "Chat Room #${viewModel.activeChatId.value}"
                        }
                    }
                }

                // 4. Observe status events SharedFlow
                launch {
                    viewModel.statusEvent.collectLatest { msg ->
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                // 5. Loading indicator
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_connected)
                binding.tvConnectionStatus.text = "WebSocket: Connected (2-Way Live)"
            }
            is ConnectionState.Connecting -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_reconnecting)
                binding.tvConnectionStatus.text = "WebSocket: Connecting..."
            }
            is ConnectionState.Reconnecting -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_reconnecting)
                binding.tvConnectionStatus.text =
                    "WebSocket: Reconnecting (${state.attempt}/${state.maxAttempts}) in ${state.delayMillis / 1000}s..."
            }
            is ConnectionState.Disconnected -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_disconnected)
                binding.tvConnectionStatus.text = "WebSocket: Disconnected"
            }
            is ConnectionState.Failed -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_disconnected)
                binding.tvConnectionStatus.text = "WebSocket: Failed (${state.message})"
            }
        }
    }

    private fun handleSelectedPhoto(uri: Uri) {
        try {
            val tempFile = copyUriToTempFile(uri)
            if (tempFile != null) {
                val chatId = viewModel.activeChatId.value
                viewModel.uploadAndSendPhoto(
                    chatId = chatId,
                    photoFile = tempFile,
                    caption = "Photo Attachment"
                )
            } else {
                Toast.makeText(this, "Failed to load selected photo", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing photo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            val fileName = getFileNameFromUri(uri) ?: "upload_${System.currentTimeMillis()}.jpg"
            val file = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    private fun redirectToAuth() {
        val intent = Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::viewModel.isInitialized) {
            viewModel.disconnectWebSocket()
        }
    }
}
