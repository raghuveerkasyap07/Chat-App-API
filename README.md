# Real-Time Android Chat Application & Kotlin SDK

[![Android SDK](https://img.shields.io/badge/Android%20SDK-API%2024%20--%2035-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-blue.svg)](https://kotlinlang.org)
[![Socket.IO](https://img.shields.io/badge/Socket.IO-v4%20Client-orange.svg)](https://socket.io)
[![OkHttp](https://img.shields.io/badge/OkHttp-4.12.0-blueviolet.svg)](https://square.github.io/okhttp/)
[![Retrofit](https://img.shields.io/badge/Retrofit-2.11.0-red.svg)](https://square.github.io/retrofit/)
[![Unit Tests](https://img.shields.io/badge/Unit%20Tests-18%2F18%20Passing-success.svg)](#unit-tests--verification)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM%20%2B%20ViewBinding-blue.svg)](#architecture)

A production-grade, real-time messaging Android application and reusable Kotlin Chat SDK architected to mirror modern enterprise messengers (such as WhatsApp and Telegram). Built with **MVVM**, **ViewBinding**, **Kotlin Coroutines (StateFlow / SharedFlow)**, **Socket.IO v4**, **OkHttp WebSocket fallback**, **Retrofit 2**, **EncryptedSharedPreferences**, and **Coil Image Loading**.

---

## Table of Contents
1. [Overview & Key Features](#overview--key-features)
2. [Team Workload Allocation](#team-workload-allocation)
3. [Architecture & Design](#architecture--design)
4. [Real-Time Socket.IO Protocol](#real-time-socketio-protocol)
5. [REST API Specifications](#rest-api-specifications)
6. [Crash Resilience & Production Hardening](#crash-resilience--production-hardening)
7. [Getting Started & Configuration](#getting-started--configuration)
8. [Build & Test Commands](#build--test-commands)
9. [Project Directory Layout](#project-directory-layout)

---

## Overview & Key Features

This client connects to a Node.js, Express, Socket.IO v4, and MySQL backend to provide a fully reactive, resilient chat experience.

- **Real-Time 2-Way Messaging**: Bidirectional text and media communication powered by Socket.IO with WebSocket transport and OkHttp fallback.
- **WhatsApp-Style Status Ticks**:
  - `Single Grey Tick (✓)`: Sent to server.
  - `Double Grey Ticks (✓✓)`: Delivered to recipient device.
  - `Double Blue Ticks (✓✓)`: Seen / read by recipient.
- **Typing Indicators**: Active typing broadcast with a 300ms debounce and a 3-second automatic decay timer.
- **Presence & Online Status**: Real-time user online/offline broadcast and header indicators.
- **Multipart Photo Attachments**: Seamless REST upload (`POST /api/chats/{chatId}/attachments`) with immediate atomic socket broadcast and asynchronous Coil rendering.
- **Dynamic Server URL Configuration**: Configure backend host/port on the fly directly in the Auth screen without recompiling the application.
- **Edge-to-Edge Soft Keyboard Handling**: Fluid input positioning using `WindowInsetsCompat.Type.ime()`.
- **Zero-Crash Resilience**: Android Keystore corruption recovery and Gson deserialization null-safety.

---

## Team Workload Allocation

*Sprint Evaluation & Review: **Subhadeep***

| Team Member | Workload Level | Core Responsibilities |
|---|:---:|---|
| **Raghuveer** | **High** | **Core Engine, WebSockets, REST & Media Upload APIs**<br>• Socket.IO v4 & WebSocket client architecture (`SocketIOManager`, `WebSocketClientManager`)<br>• JWT Authentication pipeline, dynamic host-rewriting `AuthInterceptor`, Keystore recovery<br>• REST repositories (`AuthRepository`, `ChatRepository`, `MediaRepository`)<br>• Multipart photo upload pipeline with REST upload + atomic socket broadcast<br>• Kotlin SDK facade (`ChatClient`) with Coroutines `StateFlow` & `SharedFlow`<br>• Comprehensive crash hardening, defensive deserialization, and window insets |
| **Deepa** | **Moderate** | **UI Screens & Photo Picker**<br>• Contact and thread switching interfaces (`ContactAdapter`)<br>• Media attachment picker (`ActivityResultLauncher`)<br>• Message list adapters and multi-viewtype handling |
| **Ajit** | **Targeted** | **Image Layouts, Drawables & Push Notifications**<br>• XML bubble layouts (`item_message_sent`, `item_message_received`)<br>• Attachment action icons and UI assets<br>• Coil image caching and placeholder configuration<br>• FCM Push notification infrastructure |

---

## Architecture & Design

The application adheres strictly to the **Clean MVVM (Model-View-ViewModel)** architectural pattern with reactive data flows.

```
+-------------------------------------------------------------------------+
|                               UI LAYER                                  |
|   AuthActivity  /  MainActivity  /  MessageAdapter  /  ContactAdapter   |
|              (ViewBinding, WindowInsetsCompat, Soft-Keyboard)           |
+------------------------------------+------------------------------------+
                                     |  Observes StateFlow / SharedFlow
                                     v
+-------------------------------------------------------------------------+
|                          VIEWMODEL LAYER                                |
|                           MainViewModel                                 |
|       (UI State Management, Coroutine Scope, Debounced Typing)          |
+------------------------------------+------------------------------------+
                                     |  Calls SDK Facade
                                     v
+-------------------------------------------------------------------------+
|                        SDK DOMAIN LAYER                                 |
|                              ChatClient                                 |
|               (Builder Pattern, Lifecycle Orchestrator)                 |
+-----------------+-----------------------------------+-------------------+
                  |                                   |
                  v                                   v
+------------------------------------+  +---------------------------------+
|            DATA LAYER: REST        |  |     DATA LAYER: REAL-TIME       |
|  - AuthRepository                  |  |  - SocketIOManager              |
|  - ChatRepository                  |  |    (Socket.IO v4 Client Engine) |
|  - MediaRepository                 |  |  - WebSocketClientManager       |
|  - AuthInterceptor (JWT + Host)    |  |    (OkHttp Fallback)            |
|  - Retrofit + OkHttp               |  |                                 |
+-----------------+------------------+  +----------------+----------------+
                  |                                      |
                  v                                      v
          +---------------+                      +---------------+
          |  REST API     |                      |  Socket.IO    |
          |  (Port 5000)  |                      |  (Port 5000)  |
          +---------------+                      +---------------+
```

### SDK Reactive Flows
- `connectionState: StateFlow<ConnectionState>`: Emits `Disconnected`, `Connecting`, `Connected`, and `Error`.
- `incomingMessages: SharedFlow<ChatMessage>`: Broadcasts real-time messages directly to the active chat screen.
- `messageReceipts: SharedFlow<ReceiptPayload>`: Delivers delivered and seen receipts.
- `typingEvents: SharedFlow<TypingPayload>`: Emits typing and stop-typing events per chat and user.
- `presenceEvents: SharedFlow<PresencePayload>`: Broadcasts live online and offline transitions.

---

## Real-Time Socket.IO Protocol

The client establishes a persistent connection to the server passing the JWT token via handshake parameters:
```kotlin
val options = IO.Options.builder()
    .setQuery("token=$jwtToken")
    .setTransports(arrayOf(WebSocket.NAME))
    .setReconnection(true)
    .setReconnectionAttempts(10)
    .setReconnectionDelay(1000)
    .build()
```

### Event Specifications

| Event Name | Direction | Payload Structure | Description |
|---|:---:|---|---|
| `join_chat` | Client → Server | `{"chatId": 1}` | Emitted when opening a conversation to join its real-time room. |
| `leave_chat` | Client → Server | `{"chatId": 1}` | Emitted when leaving the chat or switching contacts. |
| `send_message` | Client → Server | `{"chatId": 1, "recipientId": 2, "content": "Hello", "mediaUrl": null}` | Sends a message. Server confirms via ack callback with server ID. |
| `new_message` | Server → Client | `{"id": 101, "chatId": 1, "senderId": 2, "content": "Hi", "mediaUrl": null, "status": "sent"}` | Incoming real-time message; client automatically sends `message_delivered`. |
| `message_delivered` | Bidirectional | `{"messageId": 101, "chatId": 1, "deliveredTo": 2}` | Marks message as received on recipient's device (Double Grey Ticks). |
| `message_seen` | Bidirectional | `{"chatId": 1, "seenBy": 2, "lastMessageId": 101}` | Emitted when recipient views active conversation (Double Blue Ticks). |
| `typing` | Bidirectional | `{"chatId": 1, "userId": 2}` | Broadcasts active typing indicator (with 300ms debounce). |
| `stop_typing` | Bidirectional | `{"chatId": 1, "userId": 2}` | Sent after 3 seconds of inactivity or on message send. |
| `user_online` | Server → Client | `{"userId": 2}` | Notifies that contact is currently online. |
| `user_offline` | Server → Client | `{"userId": 2}` | Notifies that contact went offline. |

---

## REST API Specifications

The application uses Retrofit 2 with dynamic interceptors for authentication and session persistence.

### Authentication
- `POST /api/auth/register`: Create user account (`{ name, email, password }`). Returns token and user profile.
- `POST /api/auth/login`: Authenticate existing user (`{ email, password }`). Returns token and user profile.
- `GET /api/auth/me`: Fetch authenticated user profile.

### Contacts & Chats
- `GET /api/users`: Retrieve all registered users to populate the contact list.
- `GET /api/chats`: Retrieve all existing chat threads for the current user.
- `POST /api/chats`: Create or retrieve a 1-on-1 chat room (`{ recipientId: Int }`).
- `GET /api/chats/{chatId}/messages`: Fetch paginated chat history.
- `POST /api/chats/{chatId}/messages`: REST fallback for sending messages.

### Multipart Media Uploads
- `POST /api/chats/{chatId}/attachments`: Uploads image attachment via `multipart/form-data` with form field `file`. Returns `{ success: true, data: { url: "/uploads/image-123.jpg", mediaType: "image/jpeg", size: 1048576 } }`.
- **Atomic Two-Step Pipeline**: The client uploads the photo via REST and immediately transmits the confirmed image URL over the open socket connection using `send_message`, guaranteeing instantaneous live delivery without page reload.

---

## Crash Resilience & Production Hardening

The codebase includes defensive hardening measures against common production failures:

1. **Bulletproof Gson Deserialization**:
   - `ChatMessage.kt`, `User.kt`, and `Chat.kt` utilize nullable fields with safe defaults, preventing Kotlin non-null runtime assertions (`NullPointerException`) when third-party or older server payloads omit fields.
2. **Android Keystore Auto-Recovery**:
   - `SessionManager.kt` wraps `EncryptedSharedPreferences` initialization in a `try/catch` block for `AEADBadTagException` and `GeneralSecurityException`. On cryptographic corruption (e.g. after OS updates or reinstallations), the corrupted keys are automatically purged and recreated gracefully.
3. **Soft-Keyboard Edge-to-Edge Layout**:
   - `MainActivity.kt` implements `ViewCompat.setOnApplyWindowInsetsListener` targeting `WindowInsetsCompat.Type.ime()`, dynamically pushing the chat input box above the keyboard so the input field and attachment buttons are never obscured.
4. **Chat ID Boundary Protection**:
   - Explicit guards for `chatId <= 0` prevent sending socket events before a conversation thread has resolved.
5. **Lifecycle-Safe Dialogs & Coroutine Scopes**:
   - Progress dialogs verify `!isFinishing && !isDestroyed` before showing/dismissing to avoid `WindowManager.BadTokenException`.

---

## Getting Started & Configuration

### Prerequisites
- **Android Studio**: Ladybug / Koala / Hedgehog (2024.1+)
- **JDK**: Java 11 or higher
- **Android SDK**: `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`
- **Backend**: Node.js chat server running with Socket.IO v4 on port `5000`

### Dynamic Server IP Configuration
When launching the app, the **Auth Screen** provides a **Backend Server URL** input field:
- **Android Studio Emulator**: Use `http://10.0.2.2:5000`
- **Physical Device over Wi-Fi**: Use `http://<YOUR_LOCAL_IP>:5000` (e.g. `http://192.168.0.14:5000`)

### Demo Credentials
| User | Email | Password |
|---|---|---|
| **Alice** | `alice@example.com` | `password123` |
| **Bob** | `bob@example.com` | `password123` |

---

## Build & Test Commands

### 1. Run Unit Tests (18 Tests)
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
.\gradlew.bat testDebugUnitTest
```

### 2. Build Debug APK
```powershell
.\gradlew.bat assembleDebug
```
*Output location: `app/build/outputs/apk/debug/app-debug.apk`*

### 3. Install on Connected Device or Emulator
```powershell
.\gradlew.bat installDebug
```

---

## Project Directory Layout

```
Chat-API/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/demo/chat/
│   │   │   │   ├── ChatApplication.kt               # Application entry point & SDK lifecycle
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/
│   │   │   │   │   │   └── SessionManager.kt         # EncryptedSharedPreferences with recovery
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── ApiModels.kt              # REST request & response DTOs
│   │   │   │   │   │   ├── Chat.kt                   # Chat thread entity
│   │   │   │   │   │   ├── ChatMessage.kt            # Message model with tick & status support
│   │   │   │   │   │   ├── ConnectionState.kt        # Socket connection states
│   │   │   │   │   │   ├── MediaMetadata.kt          # Attachment DTOs
│   │   │   │   │   │   ├── SocketEvents.kt           # Socket.IO event name constants
│   │   │   │   │   │   ├── SocketPayloads.kt         # Strongly typed event payloads
│   │   │   │   │   │   └── User.kt                   # User account entity
│   │   │   │   │   └── remote/
│   │   │   │   │       ├── AuthInterceptor.kt        # Bearer token & dynamic URL rewrite
│   │   │   │   │       ├── AuthRepository.kt         # REST auth services
│   │   │   │   │       ├── ChatApiService.kt         # Retrofit API interface
│   │   │   │   │       ├── ChatRepository.kt         # REST chats and message fetching
│   │   │   │   │       ├── MediaRepository.kt        # Multipart upload service
│   │   │   │   │       ├── NetworkClientProvider.kt  # OkHttp & Retrofit factory
│   │   │   │   │       ├── SocketIOManager.kt        # Primary Socket.IO v4 client engine
│   │   │   │   │       └── WebSocketClientManager.kt # OkHttp WebSocket fallback
│   │   │   │   ├── sdk/core/
│   │   │   │   │   └── ChatClient.kt                 # Public Kotlin SDK facade
│   │   │   │   ├── ui/
│   │   │   │   │   ├── AuthActivity.kt               # Login, Register & IP setup
│   │   │   │   │   ├── ContactAdapter.kt             # Contact selection adapter
│   │   │   │   │   ├── MainActivity.kt               # Chat room, soft-keyboard & photo UI
│   │   │   │   │   ├── MainViewModel.kt              # MVVM presentation logic
│   │   │   │   │   └── MessageAdapter.kt             # Message bubble list adapter with ticks
│   │   │   │   └── utils/
│   │   │   │       └── ChatLogger.kt                 # Diagnostic logging utility
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_auth.xml             # Login / Register layout
│   │   │   │   │   ├── activity_main.xml             # Chat window layout
│   │   │   │   │   ├── item_contact.xml              # Contact list row layout
│   │   │   │   │   ├── item_message_received.xml     # Received bubble layout
│   │   │   │   │   └── item_message_sent.xml         # Sent bubble layout with ticks
│   │   │   │   └── values/                           # Colors, strings, themes
│   │   │   └── AndroidManifest.xml
│   │   └── test/java/com/demo/chat/
│   │       ├── ChatClientIntegrationTest.kt         # End-to-end SDK tests
│   │       ├── RepositoriesTest.kt                   # REST MockWebServer tests
│   │       ├── SessionManagerTest.kt                 # Secure storage tests
│   │       └── WebSocketClientManagerTest.kt         # WebSocket protocol tests
│   └── build.gradle.kts
├── postman_collection.json                           # Complete Postman API test collection
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## License

This project is distributed for educational and demonstration purposes as part of the Real-Time Chat App practice sprint.
