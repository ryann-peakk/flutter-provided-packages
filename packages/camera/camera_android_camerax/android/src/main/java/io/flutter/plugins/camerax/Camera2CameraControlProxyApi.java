// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camerax;

import android.hardware.camera2.CaptureRequest;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraControl;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.jetbrains.annotations.NotNull;

import kotlin.Result;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * ProxyApi implementation for {@link Camera2CameraControl}. This class may handle instantiating
 * native object instances that are attached to a Dart instance or handle method calls on the
 * associated native class or an instance of that class.
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
class Camera2CameraControlProxyApi extends PigeonApiCamera2CameraControl {
  Camera2CameraControlProxyApi(@NonNull ProxyApiRegistrar pigeonRegistrar) {
    super(pigeonRegistrar);
  }

  @NonNull
  @Override
  public ProxyApiRegistrar getPigeonRegistrar() {
    return (ProxyApiRegistrar) super.getPigeonRegistrar();
  }

  @NonNull
  @Override
  public Camera2CameraControl from(@NonNull CameraControl cameraControl) {
    return Camera2CameraControl.from(cameraControl);
  }

  @Override
  public void addCaptureRequestOptions(
      @NonNull Camera2CameraControl pigeonInstance,
      @NonNull CaptureRequestOptions bundle,
      @NonNull Function1<? super Result<Unit>, Unit> callback) {
    final ListenableFuture<Void> addCaptureRequestOptionsFuture =
        pigeonInstance.addCaptureRequestOptions(bundle);

    Futures.addCallback(
        addCaptureRequestOptionsFuture,
        new FutureCallback<>() {
          @Override
          public void onSuccess(Void voidResult) {
            ResultCompat.success(null, callback);
          }

          @Override
          public void onFailure(@NonNull Throwable t) {
            ResultCompat.failure(t, callback);
          }
        },
        ContextCompat.getMainExecutor(getPigeonRegistrar().getContext()));
  }

    @Override
    public void setShutterSpeed(
        @NotNull Camera2CameraControl pigeon_instance,
        long speed,
        @NotNull Function1<? super @NotNull Result<@NotNull Unit>,
        @NotNull Unit> callback) {

        android.util.Log.d("Camera2CameraControl", "Setting shutter speed to: " + speed + " nanoseconds");

        CaptureRequestOptions options =
                new CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        .setCaptureRequestOption(
                                CaptureRequest.SENSOR_EXPOSURE_TIME, speed)
                        .build();

        ListenableFuture<Void> future = pigeon_instance.addCaptureRequestOptions(options);
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(Void voidResult) {
                        android.util.Log.d("Camera2CameraControl", "Shutter speed set successfully!");
                        ResultCompat.success(null, callback);
                    }

                    @Override
                    public void onFailure(@NonNull Throwable t) {
                        android.util.Log.e("Camera2CameraControl", "Failed to set shutter speed", t);
                        ResultCompat.failure(t, callback);
                    }
                },
                ContextCompat.getMainExecutor(getPigeonRegistrar().getContext()));
    }
}
