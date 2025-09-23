// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import io.flutter.plugins.camera.types.CameraCaptureProperties;
import io.flutter.plugins.camera.types.CaptureTimeoutsWrapper;

/**
 * A callback object for tracking the progress of a {@link android.hardware.camera2.CaptureRequest}
 * submitted to the camera device.
 */
class CameraCaptureCallback extends CaptureCallback {
  private static final String TAG = "CameraCaptureCallback";
  private final CameraCaptureStateListener cameraStateListener;
  private CameraState cameraState;
  private final CaptureTimeoutsWrapper captureTimeouts;
  private final CameraCaptureProperties captureProps;
  
  // Throttling for diagnostic logging
  private long lastDiagnosticLogTime = 0;
  private long lastMismatchLogTime = 0;
  private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 30000; // Log every 30 seconds max
  private static final long MISMATCH_LOG_INTERVAL_MS = 10000; // Log mismatches every 10 seconds max
  private Long lastLoggedExposure = null;
  private Integer lastLoggedAeMode = null;
  private Boolean lastLoggedAeLock = null;

  // Lookup keys for state; overrideable for unit tests since Mockito can't mock them.
  @VisibleForTesting @NonNull
  CaptureResult.Key<Integer> aeStateKey = CaptureResult.CONTROL_AE_STATE;

  @VisibleForTesting @NonNull
  CaptureResult.Key<Integer> afStateKey = CaptureResult.CONTROL_AF_STATE;

  private CameraCaptureCallback(
      @NonNull CameraCaptureStateListener cameraStateListener,
      @NonNull CaptureTimeoutsWrapper captureTimeouts,
      @NonNull CameraCaptureProperties captureProps) {
    cameraState = CameraState.STATE_PREVIEW;
    this.cameraStateListener = cameraStateListener;
    this.captureTimeouts = captureTimeouts;
    this.captureProps = captureProps;
  }

  /**
   * Creates a new instance of the {@link CameraCaptureCallback} class.
   *
   * @param cameraStateListener instance which will be called when the camera state changes.
   * @param captureTimeouts specifying the different timeout counters that should be taken into
   *     account.
   * @return a configured instance of the {@link CameraCaptureCallback} class.
   */
  public static CameraCaptureCallback create(
      @NonNull CameraCaptureStateListener cameraStateListener,
      @NonNull CaptureTimeoutsWrapper captureTimeouts,
      @NonNull CameraCaptureProperties captureProps) {
    return new CameraCaptureCallback(cameraStateListener, captureTimeouts, captureProps);
  }

  /**
   * Gets the current {@link CameraState}.
   *
   * @return the current {@link CameraState}.
   */
  public CameraState getCameraState() {
    return cameraState;
  }

  /**
   * Sets the {@link CameraState}.
   *
   * @param state the camera is currently in.
   */
  public void setCameraState(@NonNull CameraState state) {
    cameraState = state;
  }

  private void process(CaptureResult result) {
    Integer aeState = result.get(aeStateKey);
    Integer afState = result.get(afStateKey);

    // Update capture properties
    if (result instanceof TotalCaptureResult) {
      Float lensAperture = result.get(CaptureResult.LENS_APERTURE);
      Long sensorExposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
      Integer sensorSensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
      this.captureProps.setLastLensAperture(lensAperture);
      this.captureProps.setLastSensorExposureTime(sensorExposureTime);
      this.captureProps.setLastSensorSensitivity(sensorSensitivity);
    }

    if (cameraState != CameraState.STATE_PREVIEW) {
      Log.d(
          TAG,
          "CameraCaptureCallback | state: "
              + cameraState
              + " | afState: "
              + afState
              + " | aeState: "
              + aeState);
    }

    switch (cameraState) {
      case STATE_PREVIEW:
        {
          // We have nothing to do when the camera preview is working normally.
          break;
        }
      case STATE_WAITING_FOCUS:
        {
          if (afState == null) {
            return;
          } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
              || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
            handleWaitingFocusState(aeState);
          } else if (captureTimeouts.getPreCaptureFocusing().getIsExpired()) {
            Log.w(TAG, "Focus timeout, moving on with capture");
            handleWaitingFocusState(aeState);
          }

          break;
        }
      case STATE_WAITING_PRECAPTURE_START:
        {
          // CONTROL_AE_STATE can be null on some devices
          if (aeState == null
              || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
              || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
              || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
            setCameraState(CameraState.STATE_WAITING_PRECAPTURE_DONE);
          } else if (captureTimeouts.getPreCaptureMetering().getIsExpired()) {
            Log.w(TAG, "Metering timeout waiting for pre-capture to start, moving on with capture");

            setCameraState(CameraState.STATE_WAITING_PRECAPTURE_DONE);
          }
          break;
        }
      case STATE_WAITING_PRECAPTURE_DONE:
        {
          // CONTROL_AE_STATE can be null on some devices
          if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
            cameraStateListener.onConverged();
          } else if (captureTimeouts.getPreCaptureMetering().getIsExpired()) {
            Log.w(
                TAG, "Metering timeout waiting for pre-capture to finish, moving on with capture");
            cameraStateListener.onConverged();
          }

          break;
        }
    }
  }

  private void handleWaitingFocusState(Integer aeState) {
    // CONTROL_AE_STATE can be null on some devices
    if (aeState == null || aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED) {
      cameraStateListener.onConverged();
    } else {
      cameraStateListener.onPrecapture();
    }
  }

  @Override
  public void onCaptureProgressed(
      @NonNull CameraCaptureSession session,
      @NonNull CaptureRequest request,
      @NonNull CaptureResult partialResult) {
    process(partialResult);
  }

  @Override
  public void onCaptureCompleted(
      @NonNull CameraCaptureSession session,
      @NonNull CaptureRequest request,
      @NonNull TotalCaptureResult result) {
    
    // Log detailed comparison between requested and actual camera settings
    Long requestedExposure = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
    Long actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
    Integer requestedAeMode = request.get(CaptureRequest.CONTROL_AE_MODE);
    Integer actualAeMode = result.get(CaptureResult.CONTROL_AE_MODE);
    Boolean requestedAeLock = request.get(CaptureRequest.CONTROL_AE_LOCK);
    Boolean actualAeLock = result.get(CaptureResult.CONTROL_AE_LOCK);
    Integer requestedSensitivity = request.get(CaptureRequest.SENSOR_SENSITIVITY);
    Integer actualSensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
    
    // Only log when we have manual exposure settings and throttle to avoid spam
    if (requestedExposure != null && requestedExposure > 0) {
      long currentTime = System.currentTimeMillis();
      boolean hasValueChanged = !requestedExposure.equals(lastLoggedExposure) 
                             || !requestedAeMode.equals(lastLoggedAeMode)
                             || !requestedAeLock.equals(lastLoggedAeLock);
      boolean shouldLogDiagnostic = hasValueChanged 
                                 || (currentTime - lastDiagnosticLogTime) > DIAGNOSTIC_LOG_INTERVAL_MS;
      
      // Check for mismatches but throttle them too
      boolean hasMismatch = !requestedExposure.equals(actualExposure)
                         || !requestedAeMode.equals(actualAeMode) 
                         || !requestedAeLock.equals(actualAeLock);
      boolean shouldLogMismatch = hasMismatch && (currentTime - lastMismatchLogTime) > MISMATCH_LOG_INTERVAL_MS;
      
      if (shouldLogDiagnostic || shouldLogMismatch) {
        Log.d(TAG, "=== CAPTURE RESULT COMPARISON ===");
        Log.d(TAG, "EXPOSURE_TIME - Requested: " + requestedExposure + " ns, Actual: " + actualExposure + " ns");
        Log.d(TAG, "AE_MODE - Requested: " + requestedAeMode + ", Actual: " + actualAeMode);
        Log.d(TAG, "AE_LOCK - Requested: " + requestedAeLock + ", Actual: " + actualAeLock);
        Log.d(TAG, "SENSITIVITY - Requested: " + requestedSensitivity + ", Actual: " + actualSensitivity);
        
        // Check for mismatches
        if (!requestedExposure.equals(actualExposure)) {
          Log.w(TAG, "⚠️  EXPOSURE MISMATCH! Camera ignored our setting!");
        }
        if (!requestedAeMode.equals(actualAeMode)) {
          Log.w(TAG, "⚠️  AE_MODE MISMATCH! Camera ignored our AE mode!");
        }
        if (!requestedAeLock.equals(actualAeLock)) {
          Log.w(TAG, "⚠️  AE_LOCK MISMATCH! Camera ignored our AE lock!");
        }
        Log.d(TAG, "================================");
        
        // Update throttling state
        if (shouldLogDiagnostic) {
          lastDiagnosticLogTime = currentTime;
          lastLoggedExposure = requestedExposure;
          lastLoggedAeMode = requestedAeMode;
          lastLoggedAeLock = requestedAeLock;
        }
        if (shouldLogMismatch) {
          lastMismatchLogTime = currentTime;
        }
      }
    }
    
    process(result);
  }

  /** An interface that describes the different state changes implementers can be informed about. */
  interface CameraCaptureStateListener {

    /** Called when the {@link android.hardware.camera2.CaptureRequest} has been converged. */
    void onConverged();

    /**
     * Called when the {@link android.hardware.camera2.CaptureRequest} enters the pre-capture state.
     */
    void onPrecapture();
  }
}
