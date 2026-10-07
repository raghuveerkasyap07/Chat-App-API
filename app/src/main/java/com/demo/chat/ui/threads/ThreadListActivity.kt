package com.demo.chat.ui.threads

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.demo.chat.ChatApplication
import com.demo.chat.databinding.ActivityThreadListBinding
import com.demo.chat.databinding.DialogContactsBinding
import com.demo.chat.ui.AuthActivity
import com.demo.chat.ui.ContactAdapter
import com.demo.chat.ui.chat.ChatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ThreadListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadListBinding
    private lateinit var viewModel: ThreadListViewModel
    private lateinit var threadAdapter: ThreadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val chatClient = (application as ChatApplication).chatClient
        if (!chatClient.isLoggedIn()) {
            redirectToAuth()
            return
        }

        binding = ActivityThreadListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ThreadListViewModel(chatClient)

        setupWindowInsets()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadThreads()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            binding.topAppBar.setPadding(
                binding.topAppBar.paddingLeft,
                bars.top,
                binding.topAppBar.paddingRight,
                binding.topAppBar.paddingBottom
            )
            windowInsets
        }
    }

    private fun setupRecyclerView() {
        val myId = viewModel.chatClient.session.getUserId()
        threadAdapter = ThreadAdapter(myId) { chat, partnerName ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id)
                putExtra(ChatActivity.EXTRA_PARTNER_NAME, partnerName)
            }
            startActivity(intent)
        }

        binding.rvThreads.apply {
            layoutManager = LinearLayoutManager(this@ThreadListActivity)
            adapter = threadAdapter
        }
    }

    private fun setupListeners() {
        binding.fabNewChat.setOnClickListener {
            showContactsBottomSheet()
        }

        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            redirectToAuth()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.threads.collectLatest { threads ->
                        if (threads.isEmpty()) {
                            binding.tvEmptyThreads.visibility = View.VISIBLE
                            binding.rvThreads.visibility = View.GONE
                        } else {
                            binding.tvEmptyThreads.visibility = View.GONE
                            binding.rvThreads.visibility = View.VISIBLE
                            threadAdapter.submitList(threads)
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.progressThreads.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.statusEvent.collectLatest { msg ->
                        Toast.makeText(this@ThreadListActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                launch {
                    viewModel.openChatEvent.collectLatest { (chatId, partnerName) ->
                        val intent = Intent(this@ThreadListActivity, ChatActivity::class.java).apply {
                            putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                            putExtra(ChatActivity.EXTRA_PARTNER_NAME, partnerName)
                        }
                        startActivity(intent)
                    }
                }
            }
        }

        val user = viewModel.currentUser
        binding.tvCurrentUser.text = user?.name ?: ""
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
            layoutManager = LinearLayoutManager(this@ThreadListActivity)
            adapter = contactAdapter
        }

        dialogBinding.progressContacts.visibility = View.VISIBLE
        dialogBinding.tvEmptyContacts.visibility = View.GONE

        viewModel.fetchContacts()

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

    private fun redirectToAuth() {
        val intent = Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
