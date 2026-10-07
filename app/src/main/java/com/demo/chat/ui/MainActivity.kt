package com.demo.chat.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.demo.chat.ChatApplication
import com.demo.chat.R
import com.demo.chat.data.model.ConnectionState
import com.demo.chat.databinding.ActivityMainBinding
import com.demo.chat.databinding.DialogContactsBinding
import com.demo.chat.sdk.core.ChatClient
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainViewModelFactory(
    private val chatClient: ChatClient
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(chatClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var messageAdapter: MessageAdapter

    private val typingHandler = Handler(Looper.getMainLooper())
    private val stopTypingRunnable = Runnable {
        if (::viewModel.isInitialized) {
            val chatId = viewModel.activeChatId.value
            viewModel.emitStopTyping(chatId)
        }
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedPhoto(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val chatClient = (application as ChatApplication).getOrCreateChatClient()

        // Verify session: If not logged in, redirect to AuthActivity
        if (!chatClient.isLoggedIn()) {
            redirectToAuth()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retain ViewModel across screen rotations and config changes
        viewModel = ViewModelProvider(this, MainViewModelFactory(chatClient))[MainViewModel::class.java]

        setupWindowInsets()
        setupRecyclerView(chatClient.session.getUserId())
        setupHeaderUserInfo()
        setupListeners()
        observeViewModel()

        // Discover active conversation and auto-connect Socket.IO
        viewModel.loadInitialConversation()
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            val chatId = viewModel.activeChatId.value
            if (chatId > 0) {
                viewModel.joinChat(chatId)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) {
            val chatId = viewModel.activeChatId.value
            typingHandler.removeCallbacks(stopTypingRunnable)
            if (chatId > 0) {
                viewModel.emitStopTyping(chatId)
                viewModel.leaveChat(chatId)
            }
        }
    }

    /**
     * Handle edge-to-edge IME (keyboard) and system insets so the message input window
     * is NEVER overridden by the keyboard or screen orientation changes.
     */
    private fun setupWindowInsets() {
        val initialTopPadding = binding.topAppBar.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            if (isFinishing || isDestroyed) return@setOnApplyWindowInsetsListener windowInsets

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
                    if (::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                        val target = messageAdapter.itemCount - 1
                        binding.recyclerViewMessages.scrollToPosition(target)
                    }
                }
            }

            windowInsets
        }

        // Keep messages scrolled to bottom when keyboard or orientation resizes the layout
        binding.recyclerViewMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && ::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                binding.recyclerViewMessages.post {
                    if (::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                        val target = messageAdapter.itemCount - 1
                        binding.recyclerViewMessages.scrollToPosition(target)
                    }
                }
            }
        }
    }

    private fun setupHeaderUserInfo() {
        val user = viewModel.currentUser
        binding.tvCurrentUser.text = if (user != null) {
            "Logged in: ${user.displayName} (${user.email ?: ""})"
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
                    if (::messageAdapter.isInitialized && messageAdapter.itemCount > 0) {
                        binding.recyclerViewMessages.scrollToPosition(messageAdapter.itemCount - 1)
                    }
                }, 150)
            }
        }

        // Typing indicator with 1.5-second debounce
        binding.etMessageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrBlank()) return
                val chatId = viewModel.activeChatId.value
                viewModel.emitTyping(chatId)

                typingHandler.removeCallbacks(stopTypingRunnable)
                typingHandler.postDelayed(stopTypingRunnable, 1500)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSendMessage.setOnClickListener {
            val text = binding.etMessageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val chatId = viewModel.activeChatId.value
                if (chatId <= 0) {
                    Toast.makeText(this, "Please select a contact to start chatting", Toast.LENGTH_SHORT).show()
                    showContactsBottomSheet()
                    return@setOnClickListener
                }
                viewModel.sendTextMessage(chatId, text)
                binding.etMessageInput.text.clear()
                typingHandler.removeCallbacks(stopTypingRunnable)
                viewModel.emitStopTyping(chatId)
            }
        }

        binding.btnAttachPhoto.setOnClickListener {
            val chatId = viewModel.activeChatId.value
            if (chatId <= 0) {
                Toast.makeText(this, "Please select a contact before sending photos", Toast.LENGTH_SHORT).show()
                showContactsBottomSheet()
                return@setOnClickListener
            }
            try {
                photoPickerLauncher.launch("image/*")
            } catch (e: Throwable) {
                Toast.makeText(this, "Unable to open photo picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSelectContact.setOnClickListener {
            showContactsBottomSheet()
        }

        binding.tvChatPartnerName.setOnLongClickListener {
            val chatId = viewModel.activeChatId.value.let { if (it > 0) it else 1 }
            com.demo.chat.utils.NotificationHelper.showChatNotification(
                this@MainActivity,
                "Test Local Notification",
                "This is a local notification test with media preview!",
                chatId,
                "https://picsum.photos/300/200"
            )
            Toast.makeText(this@MainActivity, "Test local notification triggered!", Toast.LENGTH_SHORT).show()
            true
        }

        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            redirectToAuth()
        }
    }

    private fun showContactsBottomSheet() {
        if (isFinishing || isDestroyed) return

        try {
            val dialog = BottomSheetDialog(this)
            val dialogBinding = DialogContactsBinding.inflate(layoutInflater)
            dialog.setContentView(dialogBinding.root)

            val contactAdapter = ContactAdapter { selectedUser ->
                try {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                } catch (_: Throwable) {}
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

            var dialogJob: Job? = null
            dialogJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.registeredUsers.collectLatest { users ->
                        if (!dialog.isShowing || isFinishing || isDestroyed) return@collectLatest
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

            dialog.setOnDismissListener {
                dialogJob?.cancel()
            }

            dialog.show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Failed to open contacts: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
                        if (isFinishing || isDestroyed) return@collectLatest
                        messageAdapter.submitList(messages) {
                            if (messages.isNotEmpty() && !isFinishing && !isDestroyed) {
                                binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }

                // 3. Observe active recipient
                launch {
                    viewModel.currentRecipient.collectLatest { recipient ->
                        if (isFinishing || isDestroyed) return@collectLatest
                        if (recipient != null) {
                            binding.tvChatPartnerName.text = "Chat: ${recipient.displayName}"
                        } else {
                            val activeId = viewModel.activeChatId.value
                            binding.tvChatPartnerName.text = if (activeId > 0) "Chat Room #$activeId" else "Select Contact"
                        }
                    }
                }

                // 4. Observe partner typing indicator
                launch {
                    viewModel.partnerTypingText.collectLatest { typingText ->
                        if (isFinishing || isDestroyed) return@collectLatest
                        if (typingText != null) {
                            binding.typingIndicatorTextView.text = typingText
                            binding.typingIndicatorTextView.visibility = View.VISIBLE
                        } else {
                            binding.typingIndicatorTextView.visibility = View.GONE
                        }
                    }
                }

                // 5. Observe partner online/offline presence
                launch {
                    viewModel.partnerIsOnline.collectLatest { isOnline ->
                        if (isFinishing || isDestroyed) return@collectLatest
                        when (isOnline) {
                            true -> {
                                binding.onlineStatusTextView.text = "Online"
                                binding.onlineStatusTextView.visibility = View.VISIBLE
                                binding.onlineStatusIndicator.visibility = View.VISIBLE
                            }
                            false -> {
                                binding.onlineStatusTextView.text = "Offline"
                                binding.onlineStatusTextView.visibility = View.VISIBLE
                                binding.onlineStatusIndicator.visibility = View.GONE
                            }
                            null -> {
                                binding.onlineStatusTextView.visibility = View.GONE
                                binding.onlineStatusIndicator.visibility = View.GONE
                            }
                        }
                    }
                }

                // 6. Observe status events SharedFlow
                launch {
                    viewModel.statusEvent.collectLatest { msg ->
                        if (isFinishing || isDestroyed) return@collectLatest
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                // 7. Loading indicator
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        if (isFinishing || isDestroyed) return@collectLatest
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: ConnectionState) {
        if (isFinishing || isDestroyed) return
        when (state) {
            is ConnectionState.Connected -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_connected)
                binding.tvConnectionStatus.text = "Socket.IO: Connected (Live)"
            }
            is ConnectionState.Connecting -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_reconnecting)
                binding.tvConnectionStatus.text = "Socket.IO: Connecting..."
            }
            is ConnectionState.Reconnecting -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_reconnecting)
                binding.tvConnectionStatus.text =
                    "Socket.IO: Reconnecting (${state.attempt}/${state.maxAttempts})..."
            }
            is ConnectionState.Disconnected -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_disconnected)
                binding.tvConnectionStatus.text = "Socket.IO: Disconnected"
            }
            is ConnectionState.Failed -> {
                binding.indicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_disconnected)
                binding.tvConnectionStatus.text = "Socket.IO: Error (${state.message})"
            }
        }
    }

    private fun handleSelectedPhoto(uri: Uri) {
        try {
            val chatId = viewModel.activeChatId.value
            if (chatId <= 0) {
                Toast.makeText(this, "Please select a contact before attaching photos", Toast.LENGTH_SHORT).show()
                return
            }
            val tempFile = copyUriToTempFile(uri)
            if (tempFile != null) {
                viewModel.uploadAndSendPhoto(
                    chatId = chatId,
                    photoFile = tempFile,
                    caption = "Photo Attachment"
                )
            } else {
                Toast.makeText(this, "Failed to load selected photo", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
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
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
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
            name
        } catch (_: Throwable) {
            null
        }
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
        typingHandler.removeCallbacks(stopTypingRunnable)
        if (::viewModel.isInitialized) {
            val chatId = viewModel.activeChatId.value
            if (chatId > 0) {
                viewModel.leaveChat(chatId)
            }
        }
    }
}
