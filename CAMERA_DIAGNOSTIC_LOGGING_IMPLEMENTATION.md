# Camera Diagnostic Logging Implementation Plan

## Overview
This document outlines how to add comprehensive diagnostic logging to the camera plugin to debug intermittent long-recording failures. Logs are written directly to files in the same directory as your existing logs, eliminating dependency on `flutter_native_log_handler`.

## Architecture

```
Camera.java, CameraApiImpl.java, etc.
    ↓
CameraDiagnosticLogger.java (Custom File Logger)
    ↓
/storage/emulated/0/Android/data/com.peakk.*/files/logs/camera_diagnostics_[timestamp].log
    ↓
Collected by existing LoggingService.getAllLogFiles()
    ↓
Zipped and emailed to you
```

---

## Phase 1: Initialize Logger (DONE ✅)

**File Created:** `CameraDiagnosticLogger.java`

**Features:**
- Thread-safe file writing
- Automatic log rotation at 10MB
- Device info header
- Memory tracking
- Stack trace logging
- Background thread for I/O

---

## Phase 2: Integrate into Camera Plugin

### Step 1: Initialize in CameraApiImpl.java

**Location:** `CameraApiImpl.java` → `create()` method

**Add after creating Camera instance:**

```java
import io.flutter.plugins.camera.CameraDiagnosticLogger;

// In create() method, after: camera = new Camera(...)
CameraDiagnosticLogger logger = CameraDiagnosticLogger.getInstance(activity);
if (!logger.isInitialized().get()) {
  logger.initialize();
}
logger.info("=== Camera.create() called ===");
logger.logCameraDevice("CREATE", cameraName);
logger.logMemory("Before camera open");
```

### Step 2: Add Diagnostic Logger to Camera.java

**Add field to Camera class:**

```java
public class Camera implements ... {
  private static final String TAG = "Camera";
  
  // ADD THIS:
  private final CameraDiagnosticLogger diagLog;
  
  // In constructor:
  public Camera(...) {
    this.diagLog = CameraDiagnosticLogger.getInstance(activity.getApplicationContext());
    diagLog.info("Camera instance created");
    diagLog.logMemory("Camera constructor");
    // ... rest of constructor
  }
}
```

---

## Phase 3: Critical Logging Points

### A. Camera Lifecycle Events

**1. open() method:**

```java
@SuppressLint("MissingPermission")
public void open(String imageFormatGroup) throws CameraAccessException {
  diagLog.logLifecycle("OPEN_START", "imageFormat=" + imageFormatGroup);
  diagLog.logThread("open()");
  diagLog.logMemory("Before open");
  
  // ... existing code ...
  
  cameraManager.openCamera(
    cameraProperties.getCameraName(),
    new CameraDevice.StateCallback() {
      @Override
      public void onOpened(@NonNull CameraDevice device) {
        diagLog.logLifecycle("ON_OPENED", "CameraID=" + device.getId());
        diagLog.logStateChange("CLOSED", "OPENED", "CameraDevice.StateCallback.onOpened");
        diagLog.logThread("onOpened callback");
        
        cameraDevice = new DefaultCameraDeviceWrapper(device);
        diagLog.logCameraDevice("Wrapped device", device.getId());
        
        try {
          startPreview();
          diagLog.info("startPreview() succeeded");
          // ... send initialized event ...
        } catch (CameraAccessException | RuntimeException e) {
          diagLog.error("startPreview() FAILED", e);
          diagLog.logMemory("After startPreview failure");
          // ... handle error ...
        }
      }
      
      @Override
      public void onClosed(@NonNull CameraDevice camera) {
        diagLog.logLifecycle("ON_CLOSED", "CameraID=" + camera.getId());
        diagLog.logStateChange("OPENED", "CLOSED", "CameraDevice.StateCallback.onClosed");
        diagLog.logThread("onClosed callback");
        diagLog.logCameraDevice("Device closed", camera.getId());
        
        cameraDevice = null;
        closeCaptureSession();
        diagLog.info("Sent camera closing event to Flutter");
      }
      
      @Override
      public void onDisconnected(@NonNull CameraDevice cameraDevice) {
        diagLog.logLifecycle("ON_DISCONNECTED", "CameraID=" + cameraDevice.getId());
        diagLog.logStateChange("OPENED", "DISCONNECTED", "CameraDevice.StateCallback.onDisconnected");
        diagLog.error("Camera disconnected unexpectedly", null);
        diagLog.logMemory("At disconnect");
        diagLog.logThread("onDisconnected callback");
        
        close();
        // ... send error ...
      }
      
      @Override
      public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
        diagLog.logLifecycle("ON_ERROR", "CameraID=" + cameraDevice.getId() + ", ErrorCode=" + errorCode);
        diagLog.error("CameraDevice error: code=" + errorCode + " (" + getErrorDescription(errorCode) + ")", null);
        diagLog.logMemory("At error");
        diagLog.logThread("onError callback");
        
        close();
        // ... send error ...
      }
    },
    backgroundHandler);
    
  diagLog.info("openCamera() call completed, waiting for callback...");
}

private String getErrorDescription(int errorCode) {
  switch (errorCode) {
    case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "CAMERA_IN_USE";
    case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "MAX_CAMERAS_IN_USE";
    case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "CAMERA_DISABLED";
    case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "CAMERA_DEVICE";
    case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "CAMERA_SERVICE";
    default: return "UNKNOWN";
  }
}
```

**2. close() method:**

```java
public void close() {
  diagLog.logLifecycle("CLOSE_START", "cameraDevice=" + (cameraDevice != null ? "exists" : "null"));
  diagLog.logThread("close()");
  diagLog.logMemory("Before close");
  
  if (cameraDevice != null) {
    diagLog.logCameraDevice("Closing device", null);
    cameraDevice.close();
    cameraDevice = null;
    captureSession = null;
    diagLog.info("Camera device closed and nullified");
  } else {
    diagLog.warning("close() called but cameraDevice already null");
    closeCaptureSession();
  }
  
  if (pictureImageReader != null) {
    pictureImageReader.close();
    pictureImageReader = null;
    diagLog.info("Picture image reader closed");
  }
  // ... close other resources ...
  
  diagLog.logMemory("After close");
  diagLog.info("close() completed");
}
```

**3. dispose() method:**

```java
public void dispose() {
  diagLog.logLifecycle("DISPOSE_START", "Disposing camera instance");
  diagLog.logThread("dispose()");
  diagLog.logMemory("Before dispose");
  
  close();
  flutterTexture.release();
  getDeviceOrientationManager().stop();
  
  stopBackgroundThread();
  
  diagLog.logMemory("After dispose");
  diagLog.info("dispose() completed");
  diagLog.flush(); // Ensure logs are written before disposal
}
```

### B. Background Thread Management

**startBackgroundThread():**

```java
private void startBackgroundThread() {
  diagLog.logLifecycle("BG_THREAD_START", "Starting background thread");
  diagLog.logThread("startBackgroundThread()");
  
  backgroundHandlerThread = HandlerThreadFactory.create("CameraBackground");
  backgroundHandlerThread.start();
  backgroundHandler = HandlerFactory.create(backgroundHandlerThread.getLooper());
  
  diagLog.info("Background thread started: " + backgroundHandlerThread.getName() + " (ID: " + backgroundHandlerThread.getId() + ")");
}
```

**stopBackgroundThread():**

```java
private void stopBackgroundThread() {
  diagLog.logLifecycle("BG_THREAD_STOP", "Stopping background thread");
  diagLog.logThread("stopBackgroundThread()");
  
  if (backgroundHandlerThread != null) {
    diagLog.info("Quitting background thread: " + backgroundHandlerThread.getName());
    backgroundHandlerThread.quitSafely();
    try {
      backgroundHandlerThread.join();
      diagLog.info("Background thread joined successfully");
    } catch (InterruptedException e) {
      diagLog.error("Background thread join interrupted", e);
    }
    backgroundHandlerThread = null;
  } else {
    diagLog.warning("stopBackgroundThread() called but thread already null");
  }
  backgroundHandler = null;
  diagLog.info("stopBackgroundThread() completed");
}
```

### C. Recording Events

**startVideoRecording():**

```java
public void startVideoRecording(...) {
  diagLog.logLifecycle("RECORDING_START", "filePath=" + filePath);
  diagLog.logThread("startVideoRecording()");
  diagLog.logMemory("Before start recording");
  
  try {
    prepareMediaRecorder(filePath);
    diagLog.info("MediaRecorder prepared");
    
    recordingVideo = true;
    diagLog.logStateChange("NOT_RECORDING", "RECORDING", "startVideoRecording()");
    
    createCaptureSession(...);
    diagLog.info("Capture session created for recording");
    
  } catch (IOException | CameraAccessException e) {
    diagLog.error("Failed to start video recording", e);
    diagLog.logMemory("After start recording failure");
    throw e;
  }
}
```

**stopVideoRecording():**

```java
public void stopVideoRecording(@NonNull final Result result) {
  diagLog.logLifecycle("RECORDING_STOP", "recordingVideo=" + recordingVideo);
  diagLog.logThread("stopVideoRecording()");
  diagLog.logMemory("Before stop recording");
  
  if (!recordingVideo) {
    diagLog.warning("stopVideoRecording() called but not recording");
    result.success(null);
    return;
  }
  
  recordingVideo = false;
  diagLog.logStateChange("RECORDING", "NOT_RECORDING", "stopVideoRecording()");
  
  try {
    diagLog.info("Aborting capture session");
    captureSession.abortCaptures();
    
    diagLog.info("Stopping media recorder");
    mediaRecorder.stop();
    diagLog.info("MediaRecorder stopped successfully");
    
  } catch (CameraAccessException | IllegalStateException e) {
    diagLog.error("Error during stop recording (continuing anyway)", e);
  }
  
  mediaRecorder.reset();
  diagLog.info("MediaRecorder reset");
  
  try {
    startPreview();
    diagLog.info("Preview restarted after recording");
  } catch (CameraAccessException | IllegalStateException e) {
    diagLog.error("Failed to restart preview after recording", e);
    result.error("videoRecordingFailed", e.getMessage(), null);
    return;
  }
  
  diagLog.logMemory("After stop recording");
  diagLog.info("Recording stopped successfully: " + captureFile.getAbsolutePath());
  result.success(captureFile.getAbsolutePath());
}
```

### D. Capture Session Management

**createCaptureSession():**

```java
private void createCaptureSession(int templateType, Runnable onSuccessCallback, Surface... surfaces) 
    throws CameraAccessException {
  diagLog.logLifecycle("CREATE_CAPTURE_SESSION", "templateType=" + templateType + ", surfaces=" + surfaces.length);
  diagLog.logThread("createCaptureSession()");
  diagLog.logMemory("Before create capture session");
  
  if (cameraDevice == null) {
    diagLog.error("createCaptureSession() called with null cameraDevice", null);
    throw new IllegalStateException("Camera device is null");
  }
  
  // Close existing session
  captureSession = null;
  diagLog.info("Existing capture session nullified");
  
  // Create callback
  CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
    @Override
    public void onConfigured(@NonNull CameraCaptureSession session) {
      diagLog.info("CameraCaptureSession.onConfigured - cameraDevice=" + (cameraDevice != null ? "exists" : "null"));
      diagLog.logThread("onConfigured callback");
      
      if (cameraDevice == null) {
        diagLog.error("onConfigured but camera already closed", null);
        return;
      }
      
      captureSession = session;
      diagLog.info("Capture session configured successfully");
      
      // ... update builder and start preview ...
    }
    
    @Override
    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
      diagLog.error("CameraCaptureSession.onConfigureFailed", null);
      diagLog.logThread("onConfigureFailed callback");
      diagLog.logMemory("At configure failed");
    }
    
    @Override
    public void onClosed(@NonNull CameraCaptureSession session) {
      diagLog.info("CameraCaptureSession.onClosed");
      diagLog.logThread("onClosed callback");
    }
  };
  
  // Create session...
  diagLog.info("Calling cameraDevice.createCaptureSession()");
  // ... rest of method
}
```

**closeCaptureSession():**

```java
private void closeCaptureSession() {
  diagLog.info("closeCaptureSession() - captureSession=" + (captureSession != null ? "exists" : "null"));
  diagLog.logThread("closeCaptureSession()");
  
  if (captureSession != null) {
    captureSession.close();
    captureSession = null;
    diagLog.info("Capture session closed and nullified");
  } else {
    diagLog.warning("closeCaptureSession() called but session already null");
  }
}
```

---

## Phase 4: Race Condition Prevention (from Tilo's Fork)

### Apply Fixes While Adding Logs

**1. In CameraApiImpl.java create() method:**

```java
@Override
public void create(...) {
  diagLog.info("=== create() called ===");
  diagLog.logCameraDevice("CREATE_REQUEST", cameraName);
  
  if (camera != null) {
    diagLog.warning("create() called but camera already exists - returning error");
    diagLog.logStateChange("CREATED", "CREATE_REJECTED", "Camera already created");
    result.error("CameraAlreadyCreated", "Camera already created. Dispose first.", null);
    return; // DON'T close existing camera!
  }
  
  // ... rest of create logic
}
```

**2. In CameraApiImpl.java dispose() method:**

```java
@Override
public void dispose() {
  diagLog.info("=== dispose() called ===");
  diagLog.logThread("dispose()");
  
  // Null it FIRST to prevent race conditions
  final Camera oldCamera = camera;
  camera = null;
  diagLog.info("Camera reference nullified");
  
  if (oldCamera != null) {
    diagLog.info("Disposing old camera instance");
    oldCamera.dispose();
    diagLog.info("Old camera disposed successfully");
  } else {
    diagLog.warning("dispose() called but camera already null");
  }
  
  result.success(null);
}
```

**3. Move stopBackgroundThread() to dispose():**

```java
// In Camera.java

public void close() {
  // ... close camera, readers, etc ...
  // DON'T call stopBackgroundThread() here!
  diagLog.info("close() completed (background thread NOT stopped)");
}

public void dispose() {
  diagLog.info("dispose() starting");
  close();
  flutterTexture.release();
  getDeviceOrientationManager().stop();
  
  stopBackgroundThread(); // MOVED HERE from close()
  
  diagLog.info("dispose() completed");
  diagLog.flush();
}
```

---

## Phase 5: Flutter Integration

No changes needed! The diagnostic logs are automatically included:

```dart
// In your existing LoggingService.getAllLogFiles()
Future<List<File>> getAllLogFiles() async {
  // This already finds ALL .log files including:
  // - app_*.log
  // - native_logs_*.log
  // - recording_cubit_*.log
  // - camera_diagnostics_*.log  ← NEW! Automatically included
  
  final entities = await logsDir.list().toList();
  return entities
      .where((entity) => entity is File && entity.path.endsWith('.log'))
      .cast<File>()
      .toList();
}
```

Your existing `_shareLogFiles()` method will automatically zip and email the new camera diagnostic logs!

---

## What Gets Logged

### Every Session:
- Device info (manufacturer, model, Android version, SDK)
- Process ID
- Available memory at session start
- Session duration

### Every Operation:
- Exact timestamp
- Thread name and ID
- Memory usage (heap used/max)
- Camera device ID
- State transitions (CLOSED → OPENED, etc.)

### Critical Events:
- Camera open/close/dispose
- Background thread start/stop
- Recording start/stop
- Capture session lifecycle
- All exceptions with stack traces
- CameraDevice callbacks (onOpened, onClosed, onDisconnected, onError)
- All race condition prevention checks

### Memory Tracking:
- Before/after major operations
- At error points
- At disconnect/error events

---

## Expected Log Output Example

```
================================================================================
CAMERA DIAGNOSTIC LOG SESSION
================================================================================
Session Start: Fri Nov 15 2024 13:37:18
Device: Xiaomi Redmi Note 10 Pro
Android: 13 (SDK 33)
App Process: 31154
Available Memory: 156 MB
================================================================================

[2024-11-15T13:37:18.123] [INFO] Camera instance created
[2024-11-15T13:37:18.125] [MEMORY] Camera constructor | Used: 89MB / Max: 384MB (23.2%)
[2024-11-15T13:37:18.127] [LIFECYCLE] OPEN_START | imageFormat=yuv420
[2024-11-15T13:37:18.128] [THREAD] open() | Thread: main (ID: 1)
[2024-11-15T13:37:18.129] [MEMORY] Before open | Used: 89MB / Max: 384MB (23.2%)
[2024-11-15T13:37:18.145] [INFO] openCamera() call completed, waiting for callback...
[2024-11-15T13:37:18.234] [LIFECYCLE] ON_OPENED | CameraID=0
[2024-11-15T13:37:18.235] [STATE] 'CLOSED' -> 'OPENED' | Reason: CameraDevice.StateCallback.onOpened
[2024-11-15T13:37:18.236] [THREAD] onOpened callback | Thread: CameraBackground (ID: 42)
...

[After 62 minutes of recording]
[2024-11-15T15:18:01.456] [LIFECYCLE] RECORDING_STOP | recordingVideo=true
[2024-11-15T15:18:01.457] [THREAD] stopVideoRecording() | Thread: main (ID: 1)
[2024-11-15T15:18:01.458] [MEMORY] Before stop recording | Used: 312MB / Max: 384MB (81.3%)
[2024-11-15T15:18:01.459] [INFO] Aborting capture session
[2024-11-15T15:18:01.467] [INFO] Stopping media recorder
[2024-11-15T15:18:03.599] [INFO] MediaRecorder stopped successfully
[2024-11-15T15:18:03.601] [INFO] MediaRecorder reset
[2024-11-15T15:18:03.650] [INFO] Preview restarted after recording
[2024-11-15T15:18:03.651] [MEMORY] After stop recording | Used: 298MB / Max: 384MB (77.6%)
[2024-11-15T15:18:03.679] [LIFECYCLE] ON_DISCONNECTED | CameraID=0  ← IF THIS HAPPENS
[2024-11-15T15:18:03.680] [STATE] 'OPENED' -> 'DISCONNECTED' | Reason: CameraDevice.StateCallback.onDisconnected
[2024-11-15T15:18:03.681] [ERROR] Camera disconnected unexpectedly
[2024-11-15T15:18:03.682] [MEMORY] At disconnect | Used: 298MB / Max: 384MB (77.6%)
```

---

## Benefits

1. **Self-Contained**: No dependency on external plugins
2. **Comprehensive**: Logs everything needed to debug race conditions
3. **Thread-Safe**: Background I/O prevents blocking camera operations
4. **Auto-Rotating**: Won't fill up storage
5. **Integrated**: Works with your existing log collection system
6. **Performance**: Minimal overhead on camera operations
7. **Detailed**: Thread IDs, memory, timestamps, stack traces

---

## Next Steps

1. ✅ Custom logger created
2. ⏳ Add logging to Camera.java lifecycle methods
3. ⏳ Add logging to CameraApiImpl.java
4. ⏳ Apply race condition fixes from Tilo's fork
5. ⏳ Test locally with long recording
6. ⏳ Deploy to beta tester
7. ⏳ Wait for issue to reproduce
8. ⏳ Collect logs and analyze

---

## Implementation Priority

### High Priority (Do First):
- Camera lifecycle: open(), close(), dispose()
- CameraDevice callbacks: onOpened, onClosed, onDisconnected, onError
- Background thread management
- Race condition fixes (null-first pattern, no close in create())

### Medium Priority:
- Recording start/stop
- Capture session lifecycle
- Memory logging at key points

### Low Priority (Nice to Have):
- Individual feature logging (zoom, flash, etc.)
- Frame processing details

---

## Testing Strategy

1. **Local Test**: Record 5-minute video, check log file created
2. **Long Recording Test**: Record 60+ minute video, verify log rotation
3. **Lifecycle Test**: Lock/unlock screen during recording, check logs
4. **Memory Test**: Record until low memory, check memory logs
5. **Beta Deploy**: Send to user who experiences failures
6. **Analysis**: When failure occurs, request logs via email

The logs will tell you **exactly** what happened, when, on which thread, and with what memory state!

