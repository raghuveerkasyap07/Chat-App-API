package com.demo.chat.ui.chat.adapter

import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.databinding.ItemChatReceivedBinding
import com.demo.chat.databinding.ItemChatReceivedImageBinding
import com.demo.chat.databinding.ItemChatReceivedVideoBinding
import com.demo.chat.databinding.ItemChatReceivedAudioBinding
import com.demo.chat.databinding.ItemChatSentBinding
import com.demo.chat.databinding.ItemChatSentImageBinding
import com.demo.chat.databinding.ItemChatSentVideoBinding
import com.demo.chat.databinding.ItemChatSentAudioBinding
import com.demo.chat.databinding.ItemChatSystemBinding

class ChatAdapter(
    private val currentUserId: Int,
    private val onImageClick: (String) -> Unit,
    private val onMessageLongClick: (ChatMessage) -> Boolean
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatMessageDiffCallback) {

    companion object {
        private const val VIEW_TYPE_SENT_TEXT = 1
        private const val VIEW_TYPE_SENT_IMAGE = 2
        private const val VIEW_TYPE_RECEIVED_TEXT = 3
        private const val VIEW_TYPE_RECEIVED_IMAGE = 4
        private const val VIEW_TYPE_SYSTEM = 5
        private const val VIEW_TYPE_SENT_VIDEO = 6
        private const val VIEW_TYPE_RECEIVED_VIDEO = 7
        private const val VIEW_TYPE_SENT_AUDIO = 8
        private const val VIEW_TYPE_RECEIVED_AUDIO = 9
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        val isOutgoing = msg.isSentBy(currentUserId)

        return when {
            (msg.type ?: ChatMessage.TYPE_TEXT).equals(ChatMessage.TYPE_SYSTEM, ignoreCase = true) -> VIEW_TYPE_SYSTEM
            msg.isVideo -> if (isOutgoing) VIEW_TYPE_SENT_VIDEO else VIEW_TYPE_RECEIVED_VIDEO
            msg.isAudio -> if (isOutgoing) VIEW_TYPE_SENT_AUDIO else VIEW_TYPE_RECEIVED_AUDIO
            msg.isImage || !msg.mediaUrl.isNullOrBlank() -> {
                if (isOutgoing) VIEW_TYPE_SENT_IMAGE else VIEW_TYPE_RECEIVED_IMAGE
            }
            else -> {
                if (isOutgoing) VIEW_TYPE_SENT_TEXT else VIEW_TYPE_RECEIVED_TEXT
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SENT_TEXT -> SentTextViewHolder(ItemChatSentBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SENT_IMAGE -> SentImageViewHolder(ItemChatSentImageBinding.inflate(inflater, parent, false))
            VIEW_TYPE_RECEIVED_TEXT -> ReceivedTextViewHolder(ItemChatReceivedBinding.inflate(inflater, parent, false))
            VIEW_TYPE_RECEIVED_IMAGE -> ReceivedImageViewHolder(ItemChatReceivedImageBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SENT_VIDEO -> SentVideoViewHolder(ItemChatSentVideoBinding.inflate(inflater, parent, false))
            VIEW_TYPE_RECEIVED_VIDEO -> ReceivedVideoViewHolder(ItemChatReceivedVideoBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SENT_AUDIO -> SentAudioViewHolder(ItemChatSentAudioBinding.inflate(inflater, parent, false))
            VIEW_TYPE_RECEIVED_AUDIO -> ReceivedAudioViewHolder(ItemChatReceivedAudioBinding.inflate(inflater, parent, false))
            else -> SystemViewHolder(ItemChatSystemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is SentTextViewHolder -> holder.bind(message)
            is SentImageViewHolder -> holder.bind(message)
            is ReceivedTextViewHolder -> holder.bind(message)
            is ReceivedImageViewHolder -> holder.bind(message)
            is SentVideoViewHolder -> holder.bind(message)
            is ReceivedVideoViewHolder -> holder.bind(message)
            is SentAudioViewHolder -> holder.bind(message)
            is ReceivedAudioViewHolder -> holder.bind(message)
            is SystemViewHolder -> holder.bind(message)
        }
    }

    inner class SentTextViewHolder(private val binding: ItemChatSentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessageBody.text = message.message
            binding.tvTimestamp.text = message.createdAt ?: ""
            binding.tvStatus.text = when (message.status ?: ChatMessage.STATUS_SENT) {
                ChatMessage.STATUS_SENDING -> "⏳"
                ChatMessage.STATUS_DELIVERED -> "✓✓"
                ChatMessage.STATUS_READ -> "✓✓"
                ChatMessage.STATUS_FAILED -> "❌"
                else -> "✓"
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }
    }

    inner class SentImageViewHolder(private val binding: ItemChatSentImageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            val url = message.mediaUrl ?: ""
            binding.ivSentImage.load(url) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_dialog_alert)
            }
            if (message.message.isNotBlank()) {
                binding.tvMessageBody.text = message.message
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }
            binding.tvTimestamp.text = message.createdAt ?: ""
            binding.tvStatus.text = when (message.status ?: ChatMessage.STATUS_SENT) {
                ChatMessage.STATUS_SENDING -> "⏳"
                ChatMessage.STATUS_DELIVERED -> "✓✓"
                else -> "✓"
            }

            binding.ivSentImage.setOnClickListener {
                if (url.isNotBlank()) onImageClick(url)
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }
    }

    inner class ReceivedTextViewHolder(private val binding: ItemChatReceivedBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessageBody.text = message.message
            binding.tvTimestamp.text = message.createdAt ?: ""
            if (message.sender != null) {
                binding.tvSenderName.text = message.sender.name
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }
    }

    inner class ReceivedImageViewHolder(private val binding: ItemChatReceivedImageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            val url = message.mediaUrl ?: ""
            binding.ivReceivedImage.load(url) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_dialog_alert)
            }
            if (message.sender != null) {
                binding.tvSenderName.text = message.sender.name
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
            if (message.message.isNotBlank()) {
                binding.tvMessageBody.text = message.message
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }
            binding.tvTimestamp.text = message.createdAt ?: ""

            binding.ivReceivedImage.setOnClickListener {
                if (url.isNotBlank()) onImageClick(url)
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }
    }

    inner class SentVideoViewHolder(private val binding: ItemChatSentVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            val url = message.mediaUrl ?: ""
            binding.ivVideoThumb.load(url) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_dialog_alert)
            }
            if (message.message.isNotBlank()) {
                binding.tvMessageBody.text = message.message
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }
            binding.tvTimestamp.text = message.createdAt ?: ""
            binding.tvStatus.text = when (message.status ?: ChatMessage.STATUS_SENT) {
                ChatMessage.STATUS_SENDING -> "⏳"
                ChatMessage.STATUS_DELIVERED -> "✓✓"
                else -> "✓"
            }

            binding.ivVideoThumb.setOnClickListener {
                if (url.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), "video/*")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try {
                        binding.root.context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(binding.root.context, "No video player found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }
    }

    inner class ReceivedVideoViewHolder(private val binding: ItemChatReceivedVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            val url = message.mediaUrl ?: ""
            binding.ivVideoThumb.load(url) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_dialog_alert)
            }
            if (message.sender != null) {
                binding.tvSenderName.text = message.sender.name
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
            if (message.message.isNotBlank()) {
                binding.tvMessageBody.text = message.message
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }
            binding.tvTimestamp.text = message.createdAt ?: ""

            binding.ivVideoThumb.setOnClickListener {
                if (url.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), "video/*")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try {
                        binding.root.context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(binding.root.context, "No video player found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }
    }

    inner class SentAudioViewHolder(private val binding: ItemChatSentAudioBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var mediaPlayer: MediaPlayer? = null
        private var isPlaying = false

        fun bind(message: ChatMessage) {
            val url = message.mediaUrl ?: ""
            binding.tvAudioTitle.text = message.metadata?.fileName ?: "Audio Message"
            binding.tvTimestamp.text = message.createdAt ?: ""
            binding.tvStatus.text = when (message.status ?: ChatMessage.STATUS_SENT) {
                ChatMessage.STATUS_SENDING -> "⏳"
                ChatMessage.STATUS_DELIVERED -> "✓✓"
                else -> "✓"
            }

            binding.btnPlayAudio.setOnClickListener {
                if (url.isNotBlank()) {
                    if (isPlaying) {
                        stopAudio()
                    } else {
                        playAudio(url)
                    }
                }
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }

        private fun playAudio(url: String) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepareAsync()
                    setOnPreparedListener { mp ->
                        mp.start()
                        this@SentAudioViewHolder.isPlaying = true
                        binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    setOnCompletionListener {
                        stopAudio()
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAudio()
                        true
                    }
                }
            } catch (_: Exception) {
                stopAudio()
            }
        }

        private fun stopAudio() {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            this@SentAudioViewHolder.isPlaying = false
            binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    inner class ReceivedAudioViewHolder(private val binding: ItemChatReceivedAudioBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var mediaPlayer: MediaPlayer? = null
        private var isPlaying = false

        fun bind(message: ChatMessage) {
            val url = message.mediaUrl ?: ""
            binding.tvAudioTitle.text = message.metadata?.fileName ?: "Audio Message"
            binding.tvTimestamp.text = message.createdAt ?: ""
            if (message.sender != null) {
                binding.tvSenderName.text = message.sender.name
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }

            binding.btnPlayAudio.setOnClickListener {
                if (url.isNotBlank()) {
                    if (isPlaying) {
                        stopAudio()
                    } else {
                        playAudio(url)
                    }
                }
            }
            binding.root.setOnLongClickListener { onMessageLongClick(message) }
        }

        private fun playAudio(url: String) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepareAsync()
                    setOnPreparedListener { mp ->
                        mp.start()
                        this@ReceivedAudioViewHolder.isPlaying = true
                        binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    setOnCompletionListener {
                        stopAudio()
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAudio()
                        true
                    }
                }
            } catch (_: Exception) {
                stopAudio()
            }
        }

        private fun stopAudio() {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            this@ReceivedAudioViewHolder.isPlaying = false
            binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    class SystemViewHolder(private val binding: ItemChatSystemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvSystemMessage.text = message.message
        }
    }
}
