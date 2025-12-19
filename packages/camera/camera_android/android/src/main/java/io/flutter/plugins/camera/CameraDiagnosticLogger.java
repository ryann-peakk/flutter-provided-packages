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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diagnostic logger for camera plugin that writes directly to files.
 * Logs camera lifecycle events, state changes, errors, and memory info.
 * Thread-safe and designed for long-running sessions.
 */
public class CameraDiagnosticLogger {
  
  /**
   * Log categories for multi-file logging system.
   * Separates critical race condition logs from verbose debugging.
   */
  public enum LogCategory {
    CRITICAL("camera_critical"),  // Main lifecycle, device callbacks, sessions, recording
    THREAD("camera_thread"),      // Background thread + null checks
    VERBOSE("camera_verbose");    // Preview, capture, memory
    
    public final String filePrefix;
    
    LogCategory(String filePrefix) {
      this.filePrefix = filePrefix;
    }
  }
  private static final String TAG = "CameraDiagLog";
  private static final String LOG_FILE_EXTENSION = ".log";
  private static final int MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024; // 5MB per file
  private static final long MAX_TOTAL_LOG_SIZE_BYTES = 50 * 1024 * 1024; // 50MB total
  private static final long MIN_FILE_AGE_DAYS = 5; // Never delete files younger than 5 days
  
  private static CameraDiagnosticLogger instance;
  
  private final Context context;
  private final Map<LogCategory, File> logFiles = new HashMap<>();
  private final Map<LogCategory, PrintWriter> logWriters = new HashMap<>();
  private final SimpleDateFormat timestampFormat;
  final AtomicBoolean isInitialized = new AtomicBoolean(false);
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  private final AtomicBoolean isRotating = new AtomicBoolean(false);
  private final static String LOG_DIRECTORY_NAME = "camera_logs";
  
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
      File logsDir = new File(context.getExternalFilesDir(null), LOG_DIRECTORY_NAME);
      if (!logsDir.exists()) {
        logsDir.mkdirs();
      }
      
      // Run cleanup before creating new log files
      Log.i(TAG, "Running camera log cleanup on initialization...");
      int ageDeletedCount = deleteOldLogs(14);
      int sizeDeletedCount = enforceMaxTotalSize();
      Log.i(TAG, "Cleanup complete: " + (ageDeletedCount + sizeDeletedCount) + " files deleted total");
      
      // Create log files for each category
      long timestamp = System.currentTimeMillis();
      sessionStartTime = timestamp;
      
      for (LogCategory category : LogCategory.values()) {
        String fileName = category.filePrefix + "_" + timestamp + LOG_FILE_EXTENSION;
        File logFile = new File(logsDir, fileName);
        PrintWriter logWriter = new PrintWriter(new FileWriter(logFile, true), true);
        
        logFiles.put(category, logFile);
        logWriters.put(category, logWriter);
      }
      
      isInitialized.set(true);
      
      // Write session header to all files
      writeSessionHeader();
      
      Log.i(TAG, "Diagnostic logger initialized with " + logFiles.size() + " files");
      
    } catch (IOException e) {
      Log.e(TAG, "Failed to initialize diagnostic logger", e);
      isInitialized.set(false);
    }
  }
  
  /**
   * Write session header with device info to all log files
   */
  private void writeSessionHeader() {
    for (LogCategory category : LogCategory.values()) {
      writeDirect(category, "=".repeat(80));
      writeDirect(category, "CAMERA DIAGNOSTIC LOG SESSION - " + category.name());
      writeDirect(category, "=".repeat(80));
      writeDirect(category, "Session Start: " + new Date(sessionStartTime));
      writeDirect(category, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
      writeDirect(category, "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
      writeDirect(category, "App Process: " + android.os.Process.myPid());
      writeDirect(category, "Available Memory: " + getAvailableMemoryMB() + " MB");
      writeDirect(category, "=".repeat(80));
      writeDirect(category, "");
    }
  }
  
  /**
   * Log an info message (defaults to CRITICAL category)
   */
  public void info(@NonNull String message) {
    info(LogCategory.CRITICAL, message);
  }
  
  /**
   * Log an info message to specific category
   */
  public void info(@NonNull LogCategory category, @NonNull String message) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    log(category, "INFO", message, null);
  }
  
  /**
   * Log a debug message (defaults to CRITICAL category)
   */
  public void debug(@NonNull String message) {
    debug(LogCategory.CRITICAL, message);
  }
  
  /**
   * Log a debug message to specific category
   */
  public void debug(@NonNull LogCategory category, @NonNull String message) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    log(category, "DEBUG", message, null);
  }
  
  /**
   * Log a warning message (defaults to CRITICAL category)
   */
  public void warning(@NonNull String message) {
    warning(LogCategory.CRITICAL, message);
  }
  
  /**
   * Log a warning message to specific category
   */
  public void warning(@NonNull LogCategory category, @NonNull String message) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    log(category, "WARN", message, null);
  }
  
  /**
   * Log an error message (defaults to CRITICAL category)
   */
  public void error(@NonNull String message, @Nullable Throwable throwable) {
    error(LogCategory.CRITICAL, message, throwable);
  }
  
  /**
   * Log an error message to specific category
   */
  public void error(@NonNull LogCategory category, @NonNull String message, @Nullable Throwable throwable) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    log(category, "ERROR", message, throwable);
  }
  
  /**
   * Log camera lifecycle event (routes to CRITICAL category)
   */
  public void logLifecycle(@NonNull String event, @NonNull String details) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    log(LogCategory.CRITICAL, "LIFECYCLE", event + " | " + details, null);
  }
  
  /**
   * Log camera state change (routes to CRITICAL category)
   */
  public void logStateChange(@NonNull String fromState, @NonNull String toState, @NonNull String reason) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    log(LogCategory.CRITICAL, "STATE", String.format("'%s' -> '%s' | Reason: %s", fromState, toState, reason), null);
  }
  
  /**
   * Log memory info (routes to VERBOSE category)
   */
  public void logMemory(@NonNull String context) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    Runtime runtime = Runtime.getRuntime();
    long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    long maxMemory = runtime.maxMemory() / (1024 * 1024);
    log(LogCategory.VERBOSE, "MEMORY", String.format("%s | Used: %dMB / Max: %dMB (%.1f%%)", 
        context, usedMemory, maxMemory, (usedMemory * 100.0 / maxMemory)), null);
  }
  
  /**
   * Log thread info (routes to THREAD category)
   */
  public void logThread(@NonNull String operation) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    Thread thread = Thread.currentThread();
    log(LogCategory.THREAD, "THREAD", String.format("%s | Thread: %s (ID: %d)", 
        operation, thread.getName(), thread.getId()), null);
  }
  
  /**
   * Log camera device info (routes to CRITICAL category)
   */
  public void logCameraDevice(@NonNull String operation, @Nullable String cameraId) {
    if (isRotating.get()) {
      return; // Skip logging during rotation
    }
    if (cameraId != null) {
      log(LogCategory.CRITICAL, "CAMERA_DEVICE", operation + " | CameraID: " + cameraId, null);
    } else {
      log(LogCategory.CRITICAL, "CAMERA_DEVICE", operation + " | CameraID: null", null);
    }
  }
  
  /**
   * Core logging method
   */
  private void log(@NonNull LogCategory category, @NonNull String level, @NonNull String message, @Nullable Throwable throwable) {
    if (isRotating.get() || !isInitialized.get() || isClosed.get()) {
      // Fall back to Android log
      if (throwable != null) {
        Log.e(TAG, "[" + category.name() + "] [" + level + "] " + message, throwable);
      } else {
        Log.i(TAG, "[" + category.name() + "] [" + level + "] " + message);
      }
      return;
    }
    
    // Post to background thread
    logHandler.post(() -> {
      try {
        String timestamp = timestampFormat.format(new Date());
        String logEntry = String.format("[%s] [%s] %s", timestamp, level, message);
        
        writeDirect(category, logEntry);
        
        if (throwable != null) {
          writeDirect(category, "Exception: " + throwable.getClass().getName() + ": " + throwable.getMessage());
          for (StackTraceElement element : throwable.getStackTrace()) {
            writeDirect(category, "  at " + element.toString());
          }
        }
        
        logEntryCount++;
        
        // Check if we need to rotate log file for this category
        File categoryLogFile = logFiles.get(category);
        if (categoryLogFile != null && categoryLogFile.length() > MAX_LOG_SIZE_BYTES) {
          rotateLogFile(category);
        }
        
      } catch (Exception e) {
        Log.e(TAG, "Failed to write log entry", e);
      }
    });
  }
  
  /**
   * Write directly to file (must be called from background thread)
   */
  private synchronized void writeDirect(@NonNull LogCategory category, @NonNull String line) {
    PrintWriter writer = logWriters.get(category);
    if (writer != null && !isClosed.get()) {
      writer.println(line);
      writer.flush();
    }
  }
  
  /**
   * Rotate log file when it gets too large (per category)
   */
  private synchronized void rotateLogFile(@NonNull LogCategory category) {
    try {
      writeDirect(category, "");
      writeDirect(category, "=".repeat(80));
      writeDirect(category, "LOG FILE ROTATION - Size limit reached");
      writeDirect(category, "Total entries in this file: " + logEntryCount);
      writeDirect(category, "=".repeat(80));
      
      PrintWriter writer = logWriters.get(category);
      if (writer != null) {
        writer.close();
      }
      
      // Create new log file for this category
      long timestamp = System.currentTimeMillis();
      File oldFile = logFiles.get(category);
      File logsDir = oldFile != null ? oldFile.getParentFile() : new File(context.getExternalFilesDir(null), LOG_DIRECTORY_NAME);
      String fileName = category.filePrefix + "_" + timestamp + LOG_FILE_EXTENSION;
      File newLogFile = new File(logsDir, fileName);
      PrintWriter newWriter = new PrintWriter(new FileWriter(newLogFile, true), true);
      
      logFiles.put(category, newLogFile);
      logWriters.put(category, newWriter);
      
      writeDirect(category, "=".repeat(80));
      writeDirect(category, "ROTATED LOG FILE - Continuing session");
      writeDirect(category, "New file: " + newLogFile.getName());
      writeDirect(category, "=".repeat(80));
      writeDirect(category, "");
      
      Log.i(TAG, "Rotated " + category.name() + " log file: " + newLogFile.getAbsolutePath());
      
    } catch (IOException e) {
      Log.e(TAG, "Failed to rotate log file for category " + category.name(), e);
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
   * Flush logs to disk (all categories)
   */
  public void flush() {
    if (logHandler != null) {
      logHandler.post(() -> {
        for (PrintWriter writer : logWriters.values()) {
          if (writer != null) {
            writer.flush();
          }
        }
      });
    }
  }
  
  /**
   * Close the logger (all categories)
   */
  public synchronized void close() {
    if (isClosed.get()) {
      return;
    }
    
    isClosed.set(true);
    
    if (logHandler != null) {
      logHandler.post(() -> {
        try {
          // Write session end to all files
          for (LogCategory category : LogCategory.values()) {
            PrintWriter writer = logWriters.get(category);
            if (writer != null) {
              writeDirect(category, "");
              writeDirect(category, "=".repeat(80));
              writeDirect(category, "SESSION END");
              writeDirect(category, "Session Duration: " + ((System.currentTimeMillis() - sessionStartTime) / 1000) + " seconds");
              writeDirect(category, "Total Log Entries: " + logEntryCount);
              writeDirect(category, "=".repeat(80));
              
              writer.flush();
              writer.close();
            }
          }
          
          logWriters.clear();
          logFiles.clear();
          
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
   * Get current log file path (deprecated - use getAllLogFilePaths)
   */
  @Nullable
  @Deprecated
  public String getLogFilePath() {
    File criticalFile = logFiles.get(LogCategory.CRITICAL);
    return criticalFile != null ? criticalFile.getAbsolutePath() : null;
  }
  
  /**
   * Rotate camera log files (close current files, prepare for upload).
   * Called by Flutter before log upload.
   * 
   * CRITICAL: Sets isRotating flag to block all log writes during rotation.
   * This prevents corrupted/incomplete log files during upload.
   * 
   * @return List of log file paths that were rotated
   */
  public synchronized List<String> rotateLogFilesForUpload() {
    // Prevent double rotation
    if (isRotating.get()) {
      Log.w(TAG, "Log rotation already in progress, skipping");
      return new ArrayList<>();
    }
    
    if (isClosed.get()) {
      return new ArrayList<>();
    }
    
    // Block all log writes during rotation
    isRotating.set(true);
    
    List<String> rotatedPaths = new ArrayList<>();
    
    try {
      if (logHandler != null) {
        // Post to background thread to ensure all pending logs are written
        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> paths = new ArrayList<>();
        
        logHandler.post(() -> {
          try {
            for (LogCategory category : LogCategory.values()) {
              PrintWriter writer = logWriters.get(category);
              File file = logFiles.get(category);
              
              if (writer != null && file != null) {
                // Write rotation marker directly (bypasses isRotating check)
                writer.println("");
                writer.println("=".repeat(80));
                writer.println("LOG ROTATION FOR UPLOAD - " + new Date());
                writer.println("=".repeat(80));
                
                writer.flush();
                writer.close();
                paths.add(file.getAbsolutePath());
              }
            }
            
            // Clear references (will be recreated on next log call)
            logWriters.clear();
            logFiles.clear();
            isInitialized.set(false);
            
            Log.i(TAG, "Rotated " + paths.size() + " camera log files for upload");
            
          } catch (Exception e) {
            Log.e(TAG, "Failed to rotate camera log files", e);
          } finally {
            latch.countDown();
          }
        });
        
        // Wait for rotation to complete (max 2 seconds)
        try {
          latch.await(2, TimeUnit.SECONDS);
          rotatedPaths.addAll(paths);
        } catch (InterruptedException e) {
          Log.e(TAG, "Timeout waiting for log rotation", e);
        }
      }
      
      return rotatedPaths;
      
    } finally {
      // Always allow logging again
      isRotating.set(false);
    }
  }
  
  /**
   * Get all camera log file paths (for discovery).
   * 
   * @return List of all camera log file paths
   */
  public List<String> getAllLogFilePaths() {
    File logsDir = new File(context.getExternalFilesDir(null), LOG_DIRECTORY_NAME);
    if (!logsDir.exists()) {
      return new ArrayList<>();
    }
    
    List<String> paths = new ArrayList<>();
    File[] files = logsDir.listFiles((dir, name) -> 
      name.startsWith("camera_") && name.endsWith(".log"));
    
    if (files != null) {
      for (File file : files) {
        paths.add(file.getAbsolutePath());
      }
    }
    
    return paths;
  }
  
  /**
   * Delete old camera log files (cleanup).
   * 
   * @param olderThanDays Delete files older than this many days
   * @return Number of files deleted
   */
  public int deleteOldLogs(int olderThanDays) {
    File logsDir = new File(context.getExternalFilesDir(null), LOG_DIRECTORY_NAME);
    if (!logsDir.exists()) {
      return 0;
    }
    
    // Enforce minimum age: never delete files less than MIN_FILE_AGE_DAYS old
    int effectiveDays = Math.max(olderThanDays, (int) MIN_FILE_AGE_DAYS);
    long cutoffTime = System.currentTimeMillis() - (effectiveDays * 24L * 60 * 60 * 1000);
    int deletedCount = 0;
    int protectedCount = 0;
    
    File[] files = logsDir.listFiles((dir, name) -> 
      name.startsWith("camera_") && name.endsWith(".log"));
    
    if (files != null) {
      for (File file : files) {
        if (file.lastModified() < cutoffTime) {
          if (file.delete()) {
            deletedCount++;
          }
        } else if (olderThanDays < MIN_FILE_AGE_DAYS) {
          // Count files that were protected by the MIN_FILE_AGE_DAYS rule
          protectedCount++;
        }
      }
    }
    
    if (protectedCount > 0) {
      Log.i(TAG, "Protected " + protectedCount + " camera log files (less than " + MIN_FILE_AGE_DAYS + " days old)");
    }
    Log.i(TAG, "Deleted " + deletedCount + " old camera log files (older than " + effectiveDays + " days)");
    return deletedCount;
  }
  
  /**
   * Enforce maximum total size limit across all camera log files.
   * Deletes oldest files (by modification time) until total size is under MAX_TOTAL_LOG_SIZE_BYTES.
   * Never deletes files less than MIN_FILE_AGE_DAYS old.
   * 
   * @return Number of files deleted
   */
  public int enforceMaxTotalSize() {
    File logsDir = new File(context.getExternalFilesDir(null), LOG_DIRECTORY_NAME);
    if (!logsDir.exists()) {
      return 0;
    }
    
    File[] files = logsDir.listFiles((dir, name) -> 
      name.startsWith("camera_") && name.endsWith(".log"));
    
    if (files == null || files.length == 0) {
      return 0;
    }
    
    // Calculate total size
    long totalSize = 0;
    for (File file : files) {
      totalSize += file.length();
    }
    
    // If under limit, nothing to do
    if (totalSize <= MAX_TOTAL_LOG_SIZE_BYTES) {
      Log.i(TAG, "Camera logs total size: " + (totalSize / 1024 / 1024) + " MB (under " + (MAX_TOTAL_LOG_SIZE_BYTES / 1024 / 1024) + " MB limit)");
      return 0;
    }
    
    // Sort files by last modified time (oldest first)
    java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
    
    long minFileAge = System.currentTimeMillis() - (MIN_FILE_AGE_DAYS * 24L * 60 * 60 * 1000);
    int deletedCount = 0;
    long deletedBytes = 0;
    
    // Delete oldest files until we're under the limit
    for (File file : files) {
      if (totalSize <= MAX_TOTAL_LOG_SIZE_BYTES) {
        break; // We've freed enough space
      }
      
      // Never delete files less than MIN_FILE_AGE_DAYS old
      if (file.lastModified() >= minFileAge) {
        continue;
      }
      
      long fileSize = file.length();
      if (file.delete()) {
        totalSize -= fileSize;
        deletedBytes += fileSize;
        deletedCount++;
      }
    }
    
    Log.i(TAG, "Enforced max total size: deleted " + deletedCount + " files (" + 
          (deletedBytes / 1024 / 1024) + " MB), new total: " + (totalSize / 1024 / 1024) + " MB");
    
    return deletedCount;
  }
}


