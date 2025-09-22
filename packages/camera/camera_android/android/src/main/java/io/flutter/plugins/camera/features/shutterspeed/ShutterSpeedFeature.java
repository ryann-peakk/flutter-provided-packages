// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera.features.shutterspeed;

import android.hardware.camera2.CaptureRequest;
import android.util.Log;
import android.util.Range;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.plugins.camera.CameraProperties;
import io.flutter.plugins.camera.features.CameraFeature;
import io.flutter.plugins.camera.types.CameraCaptureProperties;

/**
 * Controls the shutter speed setting on the camera.
 * 
 * When a manual shutter speed is set, this feature automatically:
 * - Switches to manual exposure mode (CONTROL_AE_MODE_OFF)
 * - Sets a fixed ISO of 100 for consistent, low-noise results
 * - Disables exposure compensation to avoid conflicts
 * - Clamps values to the device's supported range
 */
public class ShutterSpeedFeature extends CameraFeature<Long> {
  private static final String TAG = "ShutterSpeedFeature";
  private static final int MANUAL_ISO_VALUE = 100; // ISO 100 for clean, predictable results
  
  private Long currentSetting;
  private final CameraCaptureProperties captureProperties;

  public ShutterSpeedFeature(@NonNull CameraProperties cameraProperties, @NonNull CameraCaptureProperties captureProperties) {
    super(cameraProperties);
    this.captureProperties = captureProperties;
    
    // Log supported exposure time range
    Range<Long> range = cameraProperties.getSensorInfoExposureTimeRange();
    if (range != null) {
      Log.d(TAG, String.format("Supported exposure time range: %d ns - %d ns (%.6f ms - %.1f ms)", 
          range.getLower(), range.getUpper(), 
          range.getLower() / 1_000_000.0, range.getUpper() / 1_000_000.0));
    } else {
      Log.w(TAG, "Exposure time range not available");
    }
  }

  @NonNull
  @Override
  public String getDebugName() {
    return "ShutterSpeedFeature";
  }

  @NonNull
  @Override
  public Long getValue() {
    return currentSetting;
  }

  @Override
  public void setValue(@NonNull Long value) {
    this.currentSetting = clampToSupportedRange(value);
  }
  
  /**
   * Sets the shutter speed to a specific value in nanoseconds.
   * 
   * @param shutterSpeedNs the shutter speed in nanoseconds, null or 0 to return to auto exposure
   */
  public void setShutterSpeed(@Nullable Long shutterSpeedNs) {
    if (shutterSpeedNs == null || shutterSpeedNs == 0) {
      Log.d(TAG, "Setting shutter speed to AUTO mode");
      this.currentSetting = null;
    } else {
      Long clampedValue = clampToSupportedRange(shutterSpeedNs);
      Log.d(TAG, String.format("Setting shutter speed: requested=%d ns (%.3f ms), clamped=%d ns (%.3f ms)", 
          shutterSpeedNs, shutterSpeedNs / 1_000_000.0,
          clampedValue, clampedValue / 1_000_000.0));
      this.currentSetting = clampedValue;
    }
  }

  @Override
  public boolean checkIsSupported() {
    // We assume it's supported if the camera has manual sensor capabilities.
    // A more robust check could be added here if needed.
    return true;
  }

  @Override
  public void updateBuilder(@NonNull CaptureRequest.Builder requestBuilder) {
    if (!checkIsSupported()) {
      Log.w(TAG, "Shutter speed feature not supported, skipping");
      return;
    }
    
    if (currentSetting == null) {
      // Auto exposure mode - let camera handle exposure automatically
      Log.d(TAG, "Applying AUTO exposure mode (AE_MODE_ON)");
      requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
      // Remove any manual settings to allow auto exposure to work
      requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
      requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
      requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, null);
    } else {
      // Manual exposure mode
      Log.d(TAG, String.format("Applying MANUAL exposure mode: shutter=%d ns (%.3f ms), ISO=%d", 
          currentSetting, currentSetting / 1_000_000.0, MANUAL_ISO_VALUE));
      requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
      requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentSetting);
      requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, MANUAL_ISO_VALUE);
      requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0); // Disable EV compensation
    }
  }

  /**
   * Gets the current actual shutter speed from the camera's capture results.
   * 
   * @return the last known sensor exposure time in nanoseconds, or null if not available
   */
  @Nullable
  public Long getShutterSpeed() {
    return captureProperties.getLastSensorExposureTime();
  }
  
  /**
   * Gets the supported shutter speed range for this camera.
   * 
   * @return Range of supported exposure times in nanoseconds, or null if not available
   */
  @Nullable
  public Range<Long> getShutterSpeedRange() {
    return cameraProperties.getSensorInfoExposureTimeRange();
  }
  
  /**
   * Clamps the provided shutter speed value to the camera's supported range.
   * 
   * @param value the desired shutter speed in nanoseconds
   * @return the clamped value within the supported range
   */
  @NonNull
  private Long clampToSupportedRange(@NonNull Long value) {
    Range<Long> range = getShutterSpeedRange();
    if (range == null) {
      Log.w(TAG, "No exposure time range available, using value as-is: " + value);
      return value; // No range info available, use as-is
    }
    
    if (value < range.getLower()) {
      Log.w(TAG, String.format("Clamping shutter speed: %d ns -> %d ns (too fast)", value, range.getLower()));
      return range.getLower();
    } else if (value > range.getUpper()) {
      Log.w(TAG, String.format("Clamping shutter speed: %d ns -> %d ns (too slow)", value, range.getUpper()));
      return range.getUpper();
    } else {
      return value;
    }
  }
}
