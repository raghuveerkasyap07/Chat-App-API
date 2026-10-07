package com.demo.chat.ui.chat

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.demo.chat.ChatApplication
import com.demo.chat.R
import com.demo.chat.data.model.ConnectionState
import com.demo.chat.databinding.ActivityChatBinding
import com.demo.chat.ui.chat.adapter.ChatAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_PARTNER_NAME = "extra_partner_name"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var chatAdapter: ChatAdapter

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendPhoto(it, this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val chatId = intent.getIntExtra(EXTRA_CHAT_ID, 1)
        val partnerName = intent.getStringExtra(EXTRA_PARTNER_NAME) ?: "Chat #$chatId"

        val chatClient = (application as ChatApplication).chatClient
        val factory = ChatViewModelFactory(chatClient, chatId)
        viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupToolbar(partnerName)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupWindowInsets() {
        val initialTopPadding = binding.appBarLayout.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            binding.appBarLayout.setPadding(
                binding.appBarLayout.paddingLeft,
                bars.top + initialTopPadding,
                binding.appBarLayout.paddingRight,
                binding.appBarLayout.paddingBottom
            )
            binding.root.setPadding(
                bars.left,
                0,
                bars.right,
                bars.bottom
            )
            windowInsets
        }
    }

    private fun setupToolbar(partnerName: String) {
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }
        binding.tvChatPartnerName.text = partnerName
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            currentUserId = viewModel.currentUserId,
            onImageClick = { imageUrl ->
                showImagePreviewDialog(imageUrl)
            },
            onMessageLongClick = { message ->
                val textToCopy = if (message.message.isNotBlank()) message.message else (message.mediaUrl ?: "")
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Chat Message", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }
        )

        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupListeners() {
        binding.etMessageInput.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI

        binding.btnSendMessage.setOnClickListener {
            val text = binding.etMessageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendTextMessage(text)
                binding.etMessageInput.text.clear()
            }
        }

        binding.btnAttachPhoto.setOnClickListener {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // Quick action suggestion chips
        binding.chipHello.setOnClickListener { viewModel.sendTextMessage("Hello!") }
        binding.chipEcho.setOnClickListener { viewModel.sendTextMessage("Echo test") }
        binding.chipPing.setOnClickListener { viewModel.sendTextMessage("Ping") }
        binding.chipHowAreYou.setOnClickListener { viewModel.sendTextMessage("How are you doing?") }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messageList.collectLatest { messages ->
                        chatAdapter.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }

                launch {
                    viewModel.connectionState.collectLatest { state ->
                        updateConnectionUi(state)
                    }
                }

                launch {
                    viewModel.statusEvent.collectLatest { msg ->
                        Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: ConnectionState) {
        val colorRes: Int
        val statusText: String

        when (state) {
            is ConnectionState.Connected -> {
                colorRes = R.color.status_connected
                statusText = "Connected"
            }
            is ConnectionState.Connecting -> {
                colorRes = R.color.status_reconnecting
                statusText = "Connecting..."
            }
            is ConnectionState.Reconnecting -> {
                colorRes = R.color.status_reconnecting
                statusText = "Reconnecting (${state.attempt}/${state.maxAttempts})..."
            }
            is ConnectionState.Disconnected -> {
                colorRes = R.color.status_disconnected
                statusText = "Disconnected"
            }
            is ConnectionState.Failed -> {
                colorRes = R.color.status_disconnected
                statusText = "Error: ${state.message}"
            }
        }

        binding.viewConnectionDot.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        binding.tvConnectionStatus.text = statusText
    }

    private fun showImagePreviewDialog(imageUrl: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_image_preview)

        val ivPreview = dialog.findViewById<ImageView>(R.id.ivPreview)
        val btnClose = dialog.findViewById<View>(R.id.btnClose)

        ivPreview.load(imageUrl) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_gallery)
            error(android.R.drawable.ic_dialog_alert)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
