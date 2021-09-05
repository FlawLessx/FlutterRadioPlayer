import 'dart:async';

import 'package:flutter/services.dart';

class FlutterRadioPlayer {
  static const MethodChannel _channel =
      const MethodChannel('flutter_radio_player');

  static const EventChannel _eventChannel =
      const EventChannel("flutter_radio_player_stream");

  static const EventChannel _eventChannelMetaData =
      const EventChannel("flutter_radio_player_meta_stream");

  // constants to support event channel
  static const radio_stopped = "radio_stopped";
  static const radio_playing = "radio_playing";
  static const radio_paused = "radio_paused";
  static const radio_error = "radio_error";
  static const radio_loading = "radio_loading";

  static Stream<String?>? _isPlayingStream;
  static Stream<String?>? _metaDataStream;

  Future<void> init(
      String imgUrl, String streamURL, String playWhenReady, String stationName,
      {String notificationIconsName = "ic_launcher"}) async {
    return await _channel.invokeMethod("initService", {
      "imgUrl": imgUrl,
      "streamURL": streamURL,
      "playWhenReady": playWhenReady,
      "stationName": stationName,
      "notificationIconsName": notificationIconsName
    });
  }

  Future<bool?> play() async {
    return await _channel.invokeMethod("play");
  }

  Future<bool?> pause() async {
    return await _channel.invokeMethod("pause");
  }

  Future<bool?> playOrPause() async {
    print("Invoking platform method: playOrPause");
    return await _channel.invokeMethod("playOrPause");
  }

  Future<bool?> stop() async {
    return await _channel.invokeMethod("stop");
  }

  Future<bool?> isPlaying() async {
    bool? isPlaying = await _channel.invokeMethod("isPlaying");
    return isPlaying;
  }

  Future<void> setVolume(double volume) async {
    await _channel.invokeMethod("setVolume", {"volume": volume});
  }

  /// Get the player stream.
  Stream<String?>? get isPlayingStream {
    if (_isPlayingStream == null) {
      _isPlayingStream =
          _eventChannel.receiveBroadcastStream().map<String?>((value) => value);
    }
    return _isPlayingStream;
  }

  Stream<String?>? get metaDataStream {
    if (_metaDataStream == null) {
      _metaDataStream = _eventChannelMetaData
          .receiveBroadcastStream()
          .map<String?>((value) => value);
    }

    return _metaDataStream;
  }
}
