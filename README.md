# RDP Program

A Java-based Remote Desktop Protocol application featuring real-time screen sharing, bidirectional audio streaming, encrypted file transfer, and a polished animated launcher UI — all in a single unified program.

---

## Features

### 🖥️ Remote Desktop
- Real-time screen capture and streaming with adaptive JPEG compression
- Full mouse and keyboard control forwarding (with safety restrictions on sensitive key combos)
- Delta-frame encoding with periodic I-frames to minimize bandwidth usage
- Live FPS counter and connection status display

### 🔊 Audio Streaming
- Bidirectional audio: stream the **server's system audio** to the client, or **upload the client's audio** to the server
- Stereo, 44.1 kHz, 16-bit PCM over a dedicated TCP socket
- Automatic reconnection with exponential backoff on stream interruption
- Mute/unmute and software volume control on the client side

### 📁 File Transfer
- Send and receive files between client and server over a dedicated channel (main port + 2)
- CRC-32 integrity verification on every transfer
- Optional AES-256-GCM encryption per chunk
- Progress callbacks with percentage updates in the UI
- Filename sanitization and collision-safe destination resolution
- Configurable policy restrictions on blocked file extensions and sensitive paths

### 🔐 Encryption
- AES-256 in GCM mode (authenticated encryption, no padding)
- Per-operation random 12-byte IV prepended to ciphertext
- Thread-local Cipher and SecureRandom instances for high throughput
- Key exchange via Base64-encoded string; built-in self-test on startup
- SHA-256 password hashing utility included

### 🛡️ Security & Restrictions
- Blocks outbound transfer of executables (.exe, .bat, .ps1, .reg, etc.)
- Prevents access to sensitive system paths (C:\Windows, C:\Program Files, AppData, .ssh, .aws, etc.)
- Restricts dangerous remote key combinations (e.g. Win+R)
- Per-user sensitive folder detection (credentials, browser history, startup entries)

### 🎨 Launcher UI
- Animated splash screen with spring-physics hover cards
- Particle background, shimmer accent bar, and radial gradient rendering
- Fade-in/out transitions between launcher and role windows
- Frameless, draggable, rounded window with hardware-accelerated 2D rendering

---

## Architecture

AppLauncher
├── ClientFrame  ──►  RDPClient
│                       ├── AudioClient           (receive server audio)
│                       ├── AudioShareUploader    (upload client audio)
│                       └── FileTransferClient    (send/receive files)
│
└── ServerFrame  ──►  RDPServer
                        ├── AudioServer                 (capture & broadcast system audio)
                        ├── RemoteAudioPlaybackServer   (play audio uploaded from client)
                        └── FileTransferServer          (serve/receive files)

Shared
├── AESEncryption              (AES-256-GCM encrypt/decrypt)
├── FileTransferManager        (protocol engine used by both sides)
├── RemoteAccessRestrictions   (path & extension policy)
└── WindowsExplorerForegroundPath  (PowerShell probe for active Explorer folder)


Port Layout

Channel          Port
Main RDP         configured
Audio            main + 1
File Transfer    main + 2

---

## Requirements

- Java 25+ (uses text blocks and modern java.net APIs)
- Windows recommended for full audio loopback support (Stereo Mix / What U Hear)
- Audio streaming on non-Windows hosts requires a virtual loopback device (e.g. VB-Cable, PulseAudio monitor source)

---

## Building

With Maven:

    mvn clean package

With javac directly:

    javac -d out src/com/mycompany/rdpunifiedprogram/*.java
    jar cfe RDPUnified.jar com.mycompany.rdpunifiedprogram.AppLauncher -C out .

---

## Running

    java -jar RDPUnified.jar

The launcher will appear. Choose Server to host a session or Client to connect to one.

Server Setup
1. Click Server in the launcher.
2. Set the listening port and (optionally) enable AES encryption.
3. Click Start — the server will begin accepting connections on the main port and automatically open audio/file sub-ports.
4. To send files to a connected client, use the Queue Files button, then click Send to Client.

Client Setup
1. Click Client in the launcher.
2. Enter the server's IP address and port.
3. (Optional) Enter the shared encryption key.
4. Click Connect — the screen canvas will appear once the session is established.
5. Use the toolbar to toggle audio, upload local audio, or transfer files.

---

## Audio Notes

The server streams whatever is currently playing on the host machine's audio output. This requires a loopback capture device (also called Stereo Mix, Wave Out Mix, or a Monitor source). To enable it on Windows:

1. Right-click the speaker icon → Sounds → Recording tab.
2. Right-click in the device list → Show Disabled Devices.
3. Enable Stereo Mix and set it as default.

If no loopback device is found, the audio server will log an error and audio streaming will be skipped — the rest of the session continues normally.

---

## Security Considerations

- Encryption is optional but strongly recommended for sessions over untrusted networks. Share the AES key out-of-band before connecting.
- The file transfer layer enforces extension and path blocklists on the client side by default. The server-side manager can also be configured to enforce restrictions.
- Remote keyboard input blocks Win+R by default to prevent remote Run dialog abuse.
- No authentication mechanism beyond the shared encryption key is currently implemented. Run behind a VPN or trusted network for production use.

---

## Project Structure

src/com/mycompany/rdpunifiedprogram/
├── AppLauncher.java                   Animated launcher window & entry point
├── ClientFrame.java                   Client UI frame and toolbar
├── ServerFrame.java                   Server UI frame and log panel
├── RDPClient.java                     Client networking, screen decode, input forwarding
├── RDPServer.java                     Server networking, screen capture, input replay
├── ScreenCanvas.java                  High-performance Swing canvas for frame rendering
├── AESEncryption.java                 AES-256-GCM encryption utility
├── AudioClient.java                   Receives and plays server audio stream
├── AudioServer.java                   Captures and broadcasts system audio
├── AudioShareUploader.java            Uploads client system audio to server
├── RemoteAudioPlaybackServer.java     Plays audio uploaded by the client
├── FileTransferClient.java            Client-side file send/receive
├── FileTransferServer.java            Server-side file send/receive
├── FileTransferManager.java           Shared file transfer protocol engine
├── RemoteAccessRestrictions.java      Path/extension security policy
└── WindowsExplorerForegroundPath.java PowerShell probe for Explorer folder context
