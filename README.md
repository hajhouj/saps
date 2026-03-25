# SAPS - Simple Android Proxy Server

[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://java.com)
[![License](https://img.shields.io/badge/License-See%20LICENSE-blue.svg)](LICENSE)

A lightweight HTTP/HTTPS proxy server for Android with a user-friendly interface.

**Bundle ID:** `com.hajhouj.saps`

---

## Features

- HTTP/HTTPS proxy support (including CONNECT method for SSL/TLS tunnels)
- Configurable proxy port
- Real-time connection logging
- Foreground service with persistent notification
- Material Design UI
- Optimized for performance with large buffers and socket tuning
- Proper handling of chunked transfer encoding
- Support for keep-alive connections

---

## Project Structure

```
saps/
├── app/
│   ├── build.gradle              # App-level build configuration
│   ├── proguard-rules.pro        # ProGuard configuration
│   └── src/main/
│       ├── AndroidManifest.xml   # App manifest with permissions
│       ├── java/com/hajhouj/saps/
│       │   ├── MainActivity.java # Main UI activity
│       │   ├── ProxyService.java # Foreground service for proxy
│       │   └── ProxyServer.java  # Core HTTP/HTTPS proxy implementation
│       └── res/
│           ├── layout/activity_main.xml   # Main UI layout
│           ├── values/
│           │   ├── strings.xml   # App strings
│           │   ├── colors.xml    # Color definitions
│           │   └── themes.xml    # App theme
│           └── drawable/         # App icons
├── build.gradle                  # Project-level build configuration
├── settings.gradle               # Project settings
├── gradle.properties             # Gradle properties
└── README.md                     # This file
```

---

## Requirements

- Android SDK 24+ (Android 7.0+)
- Target SDK 34
- Java 8+
- Gradle 8.10+

---

## Building

### Prerequisites

Set the `ANDROID_HOME` environment variable:

```bash
export ANDROID_HOME=/path/to/android/sdk
```

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage

1. Launch the SAPS app on your Android device
2. Enter a port number (default: 8080)
3. Tap **"Start Proxy"**
4. Note the displayed IP address and port
5. Configure your browser/device to use the proxy:
   - **HTTP Proxy:** `<device_ip>:<port>`
   - **HTTPS Proxy:** `<device_ip>:<port>`
6. The app will show real-time logs of proxy activity

---

## Permissions

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| `INTERNET` | For network communication |
| `ACCESS_NETWORK_STATE` | To check network status |
| `ACCESS_WIFI_STATE` | To get device IP address |
| `FOREGROUND_SERVICE` | To run proxy as a foreground service |

---

## Technical Details

### ProxyServer.java

- Implements a multi-threaded HTTP/HTTPS proxy server
- Uses thread pool for handling concurrent connections (10-100 threads)
- 64KB buffer size for optimal throughput
- TCP_NODELAY enabled for reduced latency
- Socket timeouts: 30s read, 10s connect
- Proper handling of:
  - HTTP request/response forwarding
  - HTTPS CONNECT tunneling
  - Chunked transfer encoding
  - Content-Length based responses
  - Connection keep-alive

### ProxyService.java

- Android foreground service for persistent proxy operation
- Notification channel for service status
- Binds to MainActivity for UI updates
- Handles service lifecycle and cleanup

### MainActivity.java

- Material Design UI with card-based layout
- Port configuration input
- Start/Stop proxy control
- Real-time log display with timestamps
- Device IP address display
- About dialog

---

## Performance Optimizations

1. Large buffer sizes (64KB) for better throughput
2. TCP_NODELAY to disable Nagle's algorithm
3. Socket keep-alive for persistent connections
4. Dynamic thread pool with CallerRunsPolicy
5. Buffered streams for efficient I/O
6. Proper chunked encoding handling
7. Connection reuse with SO_REUSEADDR

---

## Troubleshooting

### NS_ERROR_CORRUPTED_CONTENT

- Fixed by proper HTTP response header parsing
- Proper handling of chunked transfer encoding
- Correct Content-Length handling

### Slow Proxy Performance

- Optimized with larger buffers (64KB)
- TCP_NODELAY enabled
- Increased thread pool size
- Socket buffer tuning

### Build Issues

- Ensure Java 23+ compatibility with Gradle 8.10+
- Set `ANDROID_HOME` environment variable
- Use provided Gradle wrapper or install compatible version

---

## GitHub Actions

This project includes a GitHub Actions workflow to automatically build and release APKs when tags are pushed.

### Creating a Release

```bash
# Commit your changes
git add .
git commit -m "Prepare for release"

# Create and push a tag
git tag v1.0.0
git push origin v1.0.0
```

The workflow will:
- Build both Debug and Release APKs
- Create a GitHub Release at `https://github.com/YOUR_USERNAME/saps/releases`
- Attach the APK files for download

---

## License

See [LICENSE](LICENSE) file for details.

---

## Author

**Bundle ID:** `com.hajhouj.saps`
