// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diagnostic logger for camera plugin that writes directly to files.
 * Logs camera lifecycle events, state changes, errors, and memory info.
 * Thread-safe and designed for long-running sessions.
 */
public class CameraDiagnosticLogger {
  private static final String TAG = "CameraDiagLog";
  private static final String LOG_FILE_PREFIX = "camera_diagnostics_";
  private static final String LOG_FILE_EXTENSION = ".log";
  private static final int MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024; // 10MB per file
  
  private static CameraDiagnosticLogger instance;
  
  private final Context context;
  private File logFile;
  private PrintWriter logWriter;
  private final SimpleDateFormat timestampFormat;
  final AtomicBoolean isInitialized = new AtomicBoolean(false);
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  
  // Background thread for file I/O
  private HandlerThread logThread;
  private Handler logHandler;
  
  // Session tracking
  private long sessionStartTime;
  private int logEntryCount = 0;
  
  private CameraDiagnosticLogger(Context context) {
    this.context = context.getApplicationContext();
    this.timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
  }
  
  /**
   * Get singleton instance
   */
  public static synchronized CameraDiagnosticLogger getInstance(@NonNull Context context) {
    if (instance == null) {
      instance = new CameraDiagnosticLogger(context);
    }
    return instance;
  }
  
  /**
   * Initialize the logger (call once per app session)
   */
  public synchronized void initialize() {
    if (isInitialized.get()) {
      Log.w(TAG, "Logger already initialized");
      return;
    }
    
    try {
      // Start background thread for file I/O
      logThread = new HandlerThread("CameraDiagnosticLogger");
      logThread.start();
      logHandler = new Handler(logThread.getLooper());
      
      // Create log directory
      File logsDir = new File(context.getExternalFilesDir(null), "logs");
      if (!logsDir.exists()) {
        logsDir.mkdirs();
      }
      
      // Create new log file
      long timestamp = System.currentTimeMillis();
      sessionStartTime = timestamp;
      String fileName = LOG_FILE_PREFIX + timestamp + LOG_FILE_EXTENSION;
      logFile = new File(logsDir, fileName);
      logWriter = new PrintWriter(new FileWriter(logFile, true), true);
      
      isInitialized.set(true);
      
      // Write session header
      writeSessionHeader();
      
      Log.i(TAG, "Diagnostic logger initialized: " + logFile.getAbsolutePath());
      
    } catch (IOException e) {
      Log.e(TAG, "Failed to initialize diagnostic logger", e);
      isInitialized.set(false);
    }
  }
  
  /**
   * Write session header with device info
   */
  private void writeSessionHeader() {
    writeDirect("=".repeat(80));
    writeDirect("CAMERA DIAGNOSTIC LOG SESSION");
    writeDirect("=".repeat(80));
    writeDirect("Session Start: " + new Date(sessionStartTime));
    writeDirect("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
    writeDirect("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
    writeDirect("App Process: " + android.os.Process.myPid());
    writeDirect("Available Memory: " + getAvailableMemoryMB() + " MB");
    writeDirect("=".repeat(80));
    writeDirect("");
  }
  
  /**
   * Log an info message
   */
  public void info(@NonNull String message) {
    log("INFO", message, null);
  }
  
  /**
   * Log a debug message
   */
  public void debug(@NonNull String message) {
    log("DEBUG", message, null);
  }
  
  /**
   * Log a warning message
   */
  public void warning(@NonNull String message) {
    log("WARN", message, null);
  }
  
  /**
   * Log an error message
   */
  public void error(@NonNull String message, @Nullable Throwable throwable) {
    log("ERROR", message, throwable);
  }
  
  /**
   * Log camera lifecycle event
   */
  public void logLifecycle(@NonNull String event, @NonNull String details) {
    log("LIFECYCLE", event + " | " + details, null);
  }
  
  /**
   * Log camera state change
   */
  public void logStateChange(@NonNull String fromState, @NonNull String toState, @NonNull String reason) {
    log("STATE", String.format("'%s' -> '%s' | Reason: %s", fromState, toState, reason), null);
  }
  
  /**
   * Log memory info
   */
  public void logMemory(@NonNull String context) {
    Runtime runtime = Runtime.getRuntime();
    long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    long maxMemory = runtime.maxMemory() / (1024 * 1024);
    log("MEMORY", String.format("%s | Used: %dMB / Max: %dMB (%.1f%%)", 
        context, usedMemory, maxMemory, (usedMemory * 100.0 / maxMemory)), null);
  }
  
  /**
   * Log thread info
   */
  public void logThread(@NonNull String operation) {
    Thread thread = Thread.currentThread();
    log("THREAD", String.format("%s | Thread: %s (ID: %d)", 
        operation, thread.getName(), thread.getId()), null);
  }
  
  /**
   * Log camera device info
   */
  public void logCameraDevice(@NonNull String operation, @Nullable String cameraId) {
    if (cameraId != null) {
      log("CAMERA_DEVICE", operation + " | CameraID: " + cameraId, null);
    } else {
      log("CAMERA_DEVICE", operation + " | CameraID: null", null);
    }
  }
  
  /**
   * Core logging method
   */
  private void log(@NonNull String level, @NonNull String message, @Nullable Throwable throwable) {
    if (!isInitialized.get() || isClosed.get()) {
      // Fall back to Android log
      if (throwable != null) {
        Log.e(TAG, "[" + level + "] " + message, throwable);
      } else {
        Log.i(TAG, "[" + level + "] " + message);
      }
      return;
    }
    
    // Post to background thread
    logHandler.post(() -> {
      try {
        String timestamp = timestampFormat.format(new Date());
        String logEntry = String.format("[%s] [%s] %s", timestamp, level, message);
        
        writeDirect(logEntry);
        
        if (throwable != null) {
          writeDirect("Exception: " + throwable.getClass().getName() + ": " + throwable.getMessage());
          for (StackTraceElement element : throwable.getStackTrace()) {
            writeDirect("  at " + element.toString());
          }
        }
        
        logEntryCount++;
        
        // Check if we need to rotate log file
        if (logFile.length() > MAX_LOG_SIZE_BYTES) {
          rotateLogFile();
        }
        
      } catch (Exception e) {
        Log.e(TAG, "Failed to write log entry", e);
      }
    });
  }
  
  /**
   * Write directly to file (must be called from background thread)
   */
  private synchronized void writeDirect(@NonNull String line) {
    if (logWriter != null && !isClosed.get()) {
      logWriter.println(line);
      logWriter.flush();
    }
  }
  
  /**
   * Rotate log file when it gets too large
   */
  private synchronized void rotateLogFile() {
    try {
      writeDirect("");
      writeDirect("=".repeat(80));
      writeDirect("LOG FILE ROTATION - Size limit reached");
      writeDirect("Total entries in this file: " + logEntryCount);
      writeDirect("=".repeat(80));
      
      if (logWriter != null) {
        logWriter.close();
      }
      
      // Create new log file
      long timestamp = System.currentTimeMillis();
      File logsDir = logFile.getParentFile();
      String fileName = LOG_FILE_PREFIX + timestamp + LOG_FILE_EXTENSION;
      logFile = new File(logsDir, fileName);
      logWriter = new PrintWriter(new FileWriter(logFile, true), true);
      logEntryCount = 0;
      
      writeDirect("=".repeat(80));
      writeDirect("ROTATED LOG FILE - Continuing session");
      writeDirect("New file: " + logFile.getName());
      writeDirect("=".repeat(80));
      writeDirect("");
      
      Log.i(TAG, "Rotated to new log file: " + logFile.getAbsolutePath());
      
    } catch (IOException e) {
      Log.e(TAG, "Failed to rotate log file", e);
    }
  }
  
  /**
   * Get available memory in MB
   */
  private long getAvailableMemoryMB() {
    Runtime runtime = Runtime.getRuntime();
    return (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / (1024 * 1024);
  }
  
  /**
   * Flush logs to disk
   */
  public void flush() {
    if (logHandler != null) {
      logHandler.post(() -> {
        if (logWriter != null) {
          logWriter.flush();
        }
      });
    }
  }
  
  /**
   * Close the logger
   */
  public synchronized void close() {
    if (isClosed.get()) {
      return;
    }
    
    isClosed.set(true);
    
    if (logHandler != null) {
      logHandler.post(() -> {
        try {
          if (logWriter != null) {
            writeDirect("");
            writeDirect("=".repeat(80));
            writeDirect("SESSION END");
            writeDirect("Session Duration: " + ((System.currentTimeMillis() - sessionStartTime) / 1000) + " seconds");
            writeDirect("Total Log Entries: " + logEntryCount);
            writeDirect("=".repeat(80));
            
            logWriter.flush();
            logWriter.close();
            logWriter = null;
          }
          
          if (logThread != null) {
            logThread.quitSafely();
            logThread = null;
          }
          
          Log.i(TAG, "Diagnostic logger closed");
          
        } catch (Exception e) {
          Log.e(TAG, "Error closing logger", e);
        }
      });
    }
    
    isInitialized.set(false);
  }
  
  /**
   * Get current log file path
   */
  @Nullable
  public String getLogFilePath() {
    return logFile != null ? logFile.getAbsolutePath() : null;
  }
}

