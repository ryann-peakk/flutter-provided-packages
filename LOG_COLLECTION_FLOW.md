# Log Collection Flow: How Beta User Logs Reach You

## 🔄 Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  BETA USER'S DEVICE                                         │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Camera Plugin (Java Layer)                          │  │
│  │  - Camera.java                                        │  │
│  │  - CameraApiImpl.java                                 │  │
│  │                                                        │  │
│  │        ↓ (writes directly to file)                    │  │
│  │                                                        │  │
│  │  CameraDiagnosticLogger.java                          │  │
│  │  - Thread-safe file I/O                               │  │
│  │  - Auto-rotation at 10MB                              │  │
│  │  - Lifecycle, state, memory, errors                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                        ↓                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Log Files on Device Storage                          │  │
│  │  /storage/emulated/0/Android/data/com.peakk.*/files/  │  │
│  │  logs/                                                 │  │
│  │                                                        │  │
│  │  ✓ camera_diagnostics_1763193945145.log  (NEW!)      │  │
│  │  ✓ native_logs_1763193945145.log                      │  │
│  │  ✓ recording_cubit_1763193947456.log                  │  │
│  │  ✓ recording_heartbeat_1763193999537.log              │  │
│  │  ✓ upload_monitor_1763194000123.log                   │  │
│  │  ✓ app_1763010810877.log                              │  │
│  │  ... (all other source-specific logs)                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                        ↓                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  LoggingService.getAllLogFiles()                      │  │
│  │  - Finds ALL .log files automatically                 │  │
│  │  - No code changes needed!                            │  │
│  └──────────────────────────────────────────────────────┘  │
│                        ↓                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  User taps "Share Logs" button in app                 │  │
│  │  (Home screen → debug menu)                           │  │
│  └──────────────────────────────────────────────────────┘  │
│                        ↓                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  _shareLogFiles() method                              │  │
│  │  1. Collects all .log files                           │  │
│  │  2. Creates ZIP archive                               │  │
│  │  3. Checks size (< 25MB for Gmail)                    │  │
│  │  4. Opens email composer                              │  │
│  │     - To: ryann07@peakk.in                            │  │
│  │     - Attachment: peakk_logs_1763212840441.zip        │  │
│  └──────────────────────────────────────────────────────┘  │
│                        ↓                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Email App (Gmail, Outlook, etc.)                     │  │
│  │  - User reviews and sends                             │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                         ↓
                    [ Internet ]
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  YOUR INBOX (ryann07@peakk.in)                              │
│                                                              │
│  📧 Email: "Peakk App Log Files"                            │
│  📎 Attachment: peakk_logs_1763212840441.zip (12.8 MB)      │
│                                                              │
│     ↓ (download and extract)                                │
│                                                              │
│  📁 Extracted logs:                                         │
│     ✓ camera_diagnostics_1763193945145.log ← NEW!          │
│     ✓ native_logs_1763193945145.log                         │
│     ✓ recording_cubit_1763193947456.log                     │
│     ✓ ... (all other logs)                                  │
│                                                              │
│     ↓ (analyze with grep, search, etc.)                     │
│                                                              │
│  💡 You debug the issue!                                    │
│     - See exact lifecycle events                            │
│     - Identify race conditions                              │
│     - Check memory at failure point                         │
│     - View thread IDs and timing                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 📝 What Gets Collected

### Current Logs (Already Working)
| Source | File Pattern | What It Contains |
|--------|-------------|------------------|
| **App** | `app_*.log` | General app lifecycle, errors |
| **Native** | `native_logs_*.log` | Android logcat (via flutter_native_log_handler) |
| **Recording** | `recording_cubit_*.log` | Recording state machine, video operations |
| **Heartbeat** | `recording_heartbeat_*.log` | Recording session heartbeats |
| **Upload** | `upload_monitor_*.log` | S3 upload progress, stalls |
| **Server API** | `server_api_*.log` | API calls, responses |
| **Battery** | `battery_optimization_*.log` | Xiaomi battery warnings |

### NEW Camera Diagnostic Logs (After Implementation)
| Source | File Pattern | What It Contains |
|--------|-------------|------------------|
| **Camera Diagnostics** | `camera_diagnostics_*.log` | Camera lifecycle, state transitions, thread IDs, memory, race condition detection, errors with stack traces |

---

## 🔍 Why This Approach Works

### 1. **No User Intervention Required**
- Logs written automatically during normal app use
- No need for ADB, root, or special tools
- Works on any device, any Android version

### 2. **Comprehensive Coverage**
- Dart/Flutter layer: `LoggingService` with source-specific files
- Native/Java layer: `CameraDiagnosticLogger` (NEW!)
- Android system: `flutter_native_log_handler` (being phased out)

### 3. **Self-Contained**
- All logs stored in app's private storage
- Survives app restarts
- Cleaned up automatically (keeps 20 most recent per type)

### 4. **Easy Collection**
- One button tap → email sent
- ZIP compression (typically 70-80% reduction)
- Gmail-friendly size checks

### 5. **Zero Code Changes for Collection**
```dart
// Your existing code already does this:
Future<List<File>> getAllLogFiles() async {
  final entities = await logsDir.list().toList();
  return entities
      .where((entity) => entity is File && entity.path.endsWith('.log'))
      .cast<File>()
      .toList();
}

// ✅ Automatically includes camera_diagnostics_*.log!
// ✅ No modifications needed!
```

---

## 📊 Example Timeline (What You See)

From the logs Tapan sent you, here's how you reconstructed the timeline:

```
[2024-11-15T13:37:18] User starts 62-minute recording
                      ↓
[2024-11-15T15:05:04] Recording stops, video saved
                      ↓
[2024-11-15T15:05:04] S3 upload starts (5.5GB video)
                      ↓
[2024-11-15T15:18:01] Upload at 13% (724MB / 5.5GB)
                      Heartbeat #50 (490 seconds recorded)
                      ↓
[2024-11-15T15:18:03] ⚠️ Last log entry (camera errors during stop)
                      ↓
       [ 52 MINUTE GAP - APP KILLED ]
                      ↓
[2024-11-15T16:10:24] App restarts (new process ID)
                      ⚠️ Xiaomi battery warning
                      Upload resumes
```

**With NEW camera diagnostics, you'll ALSO see:**
```
[2024-11-15T15:18:03.679] [LIFECYCLE] RECORDING_STOP
[2024-11-15T15:18:03.680] [MEMORY] Before stop: 312MB / 384MB (81.3%)
[2024-11-15T15:18:03.681] [INFO] MediaRecorder stopped
[2024-11-15T15:18:03.682] [INFO] Preview restarted
[2024-11-15T15:18:03.683] [LIFECYCLE] close() called ← KEY!
[2024-11-15T15:18:03.684] [THREAD] close() | Thread: main (ID: 1)
[2024-11-15T15:18:03.685] [LIFECYCLE] ON_DISCONNECTED | CameraID=0 ← SMOKING GUN!
[2024-11-15T15:18:03.686] [ERROR] Camera disconnected unexpectedly
[2024-11-15T15:18:03.687] [MEMORY] At disconnect: 298MB / 384MB (77.6%)
[2024-11-15T15:18:03.688] [LIFECYCLE] BG_THREAD_STOP ← If stopBackgroundThread() called too early!
       ↓
   App crashes or system kills it
```

---

## 🎯 Debugging Workflow

### When User Reports Issue:

1. **User emails you**: "App crashed after long recording"

2. **You request logs**: "Please tap Share Logs in the app and send"

3. **User taps button**: Email composer opens automatically

4. **You receive ZIP**: `peakk_logs_1763212840441.zip`

5. **Extract and search**:
   ```bash
   # Find when recording stopped
   grep "RECORDING_STOP" camera_diagnostics_*.log
   
   # Find camera errors
   grep "ERROR\|DISCONNECTED" camera_diagnostics_*.log
   
   # Find memory issues
   grep "MEMORY" camera_diagnostics_*.log | grep -E "9[0-9]\..*%"
   
   # Find thread issues
   grep "BG_THREAD_STOP" camera_diagnostics_*.log
   
   # Find race conditions
   grep "already null\|already exists" camera_diagnostics_*.log
   
   # Cross-reference with other logs
   grep -H "2024-11-15T15:18" *.log | sort
   ```

6. **Identify root cause** from comprehensive diagnostics

7. **Fix and deploy**

---

## 🚀 Quick Start

### For Beta Testers:
1. Use app normally
2. If issue occurs, tap "Share Logs" button
3. Send email (already pre-filled)
4. Done!

### For You:
1. Implement logging (follow CAMERA_DIAGNOSTIC_LOGGING_IMPLEMENTATION.md)
2. Deploy to beta
3. Wait for logs
4. Debug with full visibility
5. Ship fix

---

## 💪 Advantages Over flutter_native_log_handler

| Aspect | flutter_native_log_handler | CameraDiagnosticLogger |
|--------|----------------------------|------------------------|
| **Control** | Captures everything (noise) | Targeted, structured |
| **Format** | Raw logcat (mixed) | Clean, timestamped, categorized |
| **Performance** | Intercepts ALL logs | Only logs what you need |
| **Reliability** | Third-party dependency | Your code, your control |
| **Structure** | Flat text | Lifecycle, state, memory, threads |
| **Context** | Limited | Rich (device info, session tracking) |
| **Size** | Can be huge (33MB+) | Compact, auto-rotating |

---

## 🎉 The Result

After implementation, when a beta user experiences a failure:

1. They tap one button
2. You receive comprehensive diagnostics
3. You see **exactly** what happened:
   - Which lifecycle methods were called
   - In what order
   - On which threads
   - With what memory state
   - Which errors occurred
   - Complete stack traces

**No guesswork. No "works on my machine". Just facts.**

This is how you debug production issues at scale! 🚀

