// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// Video encoder types supported by the Android implementation.
///
/// Additional codecs can be added here as support is implemented on other
/// platforms.
enum VideoCodec {
  /// Use the platform default encoder for the selected profile.
  platformDefault,

  /// High Efficiency Video Coding (H.265 / MPEG-H Part 2).
  hevc,
}

const int kVideoCodecDefaultWireValue = -1;
const int kVideoCodecHevcWireValue = 5;

int? codecToWire(VideoCodec? codec) {
  switch (codec) {
    case VideoCodec.platformDefault:
      return kVideoCodecDefaultWireValue;
    case VideoCodec.hevc:
      return kVideoCodecHevcWireValue;
    case null:
      return null;
  }
}

VideoCodec? codecFromWire(int? value) {
  switch (value) {
    case kVideoCodecDefaultWireValue:
      return VideoCodec.platformDefault;
    case kVideoCodecHevcWireValue:
      return VideoCodec.hevc;
    case null:
      return null;
    default:
      return null;
  }
}
