/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;
import com.google.android.exoplayer2.PlayerMessage.Target;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectorResult;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.Util;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * An {@link ExoPlayer} implementation. Instances can be obtained from {@link ExoPlayerFactory}.
 */
/* package */ final class ExoPlayerImpl implements ExoPlayer {

  private static final String TAG = "ExoPlayerImpl";

  private final Renderer[] renderers;
  private final TrackSelector trackSelector;
  private final TrackSelectorResult emptyTrackSelectorResult;
  private final Handler eventHandler;
  private final ExoPlayerImplInternal internalPlayer;
  private final Handler internalPlayerHandler;
  private final CopyOnWriteArraySet<Player.EventListener> listeners;
  private final Timeline.Window window;
  private final Timeline.Period period;

  private boolean playWhenReady;
  private @RepeatMode int repeatMode;
  private boolean shuffleModeEnabled;
  private int pendingOperationAcks;
  private boolean hasPendingPrepare;
  private boolean hasPendingSeek;
  private PlaybackParameters playbackParameters;

  // Playback information when there is no pending seek/set source operation.
  private PlaybackInfo playbackInfo;

  // Playback information when there is a pending seek/set source operation.
  private int maskingWindowIndex;
  private int maskingPeriodIndex;
  private long maskingWindowPositionMs;

  /**
   * Constructs an instance. Must be called from a thread that has an associated {@link Looper}.
   *
   * @param renderers The {@link Renderer}s that will be used by the instance.
   * @param trackSelector The {@link TrackSelector} that will be used by the instance.
   * @param loadControl The {@link LoadControl} that will be used by the instance.
   * @param clock The {@link Clock} that will be used by the instance.
   */
  @SuppressLint("HandlerLeak")
  public ExoPlayerImpl(
      Renderer[] renderers, TrackSelector trackSelector, LoadControl loadControl, Clock clock) {
    Log.i(TAG, "Init " + Integer.toHexString(System.identityHashCode(this)) + " ["
        + ExoPlayerLibraryInfo.VERSION_SLASHY + "] [" + Util.DEVICE_DEBUG_INFO + "]");
    Assertions.checkState(renderers.length > 0);
    this.renderers = Assertions.checkNotNull(renderers);
    this.trackSelector = Assertions.checkNotNull(trackSelector);
    this.playWhenReady = false;
    this.repeatMode = Player.REPEAT_MODE_OFF;
    this.shuffleModeEnabled = false;
    this.listeners = new CopyOnWriteArraySet<>();
    emptyTrackSelectorResult =
        new TrackSelectorResult(
            TrackGroupArray.EMPTY,
            new boolean[renderers.length],
            new TrackSelectionArray(new TrackSelection[renderers.length]),
            null,
            new RendererConfiguration[renderers.length]);
    window = new Timeline.Window();
    period = new Timeline.Period();
    playbackParameters = PlaybackParameters.DEFAULT;
    Looper eventLooper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
    eventHandler = new Handler(eventLooper) {
      @Override
      public void handleMessage(Message msg) {
        ExoPlayerImpl.this.handleEvent(msg);
      }
    };
    playbackInfo =
        new PlaybackInfo(Timeline.EMPTY, /* startPositionUs= */ 0, emptyTrackSelectorResult);
    internalPlayer =
        new ExoPlayerImplInternal(
            renderers,
            trackSelector,
            emptyTrackSelectorResult,
            loadControl,
            playWhenReady,
            repeatMode,
            shuffleModeEnabled,
            eventHandler,
            this,
            clock);
    internalPlayerHandler = new Handler(internalPlayer.getPlaybackLooper());
  }

  @Override
  public VideoComponent getVideoComponent() {
    return null;
  }

  @Override
  public TextComponent getTextComponent() {
    return null;
  }

  @Override
  public Looper getPlaybackLooper() {
    return internalPlayer.getPlaybackLooper();
  }

  @Override
  public void addListener(Player.EventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(Player.EventListener listener) {
    listeners.remove(listener);
  }

  @Override
  public int getPlaybackState() {
    return playbackInfo.playbackState;
  }

  @Override
  public void prepare(MediaSource mediaSource) {
    prepare(mediaSource, true, true);
  }

  @Override
  public void prepare(MediaSource mediaSource, boolean resetPosition, boolean resetState) {
    PlaybackInfo playbackInfo =
        getResetPlaybackInfo(
            resetPosition, resetState, /* playbackState= */ Player.STATE_BUFFERING);
    // Trigger internal prepare first before updating the playback info and notifying external
    // listeners to ensure that new operations issued in the listener notifications reach the
    // player after this prepare. The internal player can't change the playback info immediately
    // because it uses a callback.
    hasPendingPrepare = true;
    pendingOperationAcks++;
    internalPlayer.prepare(mediaSource, resetPosition, resetState);
    updatePlaybackInfo(
        playbackInfo,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        TIMELINE_CHANGE_REASON_RESET,
        /* seekProcessed= */ false);
  }

  @Override
  public void setPlayWhenReady(boolean playWhenReady) {
    if (this.playWhenReady != playWhenReady) {
      this.playWhenReady = playWhenReady;
      internalPlayer.setPlayWhenReady(playWhenReady);
      for (Player.EventListener listener : listeners) {
        listener.onPlayerStateChanged(playWhenReady, playbackInfo.playbackState);
      }
    }
  }

  @Override
  public boolean getPlayWhenReady() {
    return playWhenReady;
  }

  @Override
  public void setRepeatMode(@RepeatMode int repeatMode) {
    if (this.repeatMode != repeatMode) {
      this.repeatMode = repeatMode;
      internalPlayer.setRepeatMode(repeatMode);
      for (Player.EventListener listener : listeners) {
        listener.onRepeatModeChanged(repeatMode);
      }
    }
  }

  @Override
  public @RepeatMode int getRepeatMode() {
    return repeatMode;
  }

  @Override
  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    if (this.shuffleModeEnabled != shuffleModeEnabled) {
      this.shuffleModeEnabled = shuffleModeEnabled;
      internalPlayer.setShuffleModeEnabled(shuffleModeEnabled);
      for (Player.EventListener listener : listeners) {
        listener.onShuffleModeEnabledChanged(shuffleModeEnabled);
      }
    }
  }

  @Override
  public boolean getShuffleModeEnabled() {
    return shuffleModeEnabled;
  }

  @Override
  public boolean isLoading() {
    return playbackInfo.isLoading;
  }

  @Override
  public void seekToDefaultPosition() {
    seekToDefaultPosition(getCurrentWindowIndex());
  }

  @Override
  public void seekToDefaultPosition(int windowIndex) {
    seekTo(windowIndex, C.TIME_UNSET);
  }

  @Override
  public void seekTo(long positionMs) {
    seekTo(getCurrentWindowIndex(), positionMs);
  }

  @Override
  public void seekTo(int windowIndex, long positionMs) {
    Timeline timeline = playbackInfo.timeline;
    if (windowIndex < 0 || (!timeline.isEmpty() && windowIndex >= timeline.getWindowCount())) {
      throw new IllegalSeekPositionException(timeline, windowIndex, positionMs);
    }
    hasPendingSeek = true;
    pendingOperationAcks++;
    if (isPlayingAd()) {
      // TODO: Investigate adding support for seeking during ads. This is complicated to do in
      // general because the midroll ad preceding the seek destination must be played before the
      // content position can be played, if a different ad is playing at the moment.
      Log.w(TAG, "seekTo ignored because an ad is playing");
      eventHandler
          .obtainMessage(
              ExoPlayerImplInternal.MSG_PLAYBACK_INFO_CHANGED,
              /* operationAcks */ 1,
              /* positionDiscontinuityReason */ C.INDEX_UNSET,
              playbackInfo)
          .sendToTarget();
      return;
    }
    maskingWindowIndex = windowIndex;
    if (timeline.isEmpty()) {
      maskingWindowPositionMs = positionMs == C.TIME_UNSET ? 0 : positionMs;
      maskingPeriodIndex = 0;
    } else {
      long windowPositionUs = positionMs == C.TIME_UNSET
          ? timeline.getWindow(windowIndex, window).getDefaultPositionUs() : C.msToUs(positionMs);
      Pair<Integer, Long> periodIndexAndPositon =
          timeline.getPeriodPosition(window, period, windowIndex, windowPositionUs);
      maskingWindowPositionMs = C.usToMs(windowPositionUs);
      maskingPeriodIndex = periodIndexAndPositon.first;
    }
    internalPlayer.seekTo(timeline, windowIndex, C.msToUs(positionMs));
    for (Player.EventListener listener : listeners) {
      listener.onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK);
    }
  }

  @Override
  public void setPlaybackParameters(@Nullable PlaybackParameters playbackParameters) {
    if (playbackParameters == null) {
      playbackParameters = PlaybackParameters.DEFAULT;
    }
    internalPlayer.setPlaybackParameters(playbackParameters);
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return playbackParameters;
  }

  @Override
  public void setSeekParameters(@Nullable SeekParameters seekParameters) {
    if (seekParameters == null) {
      seekParameters = SeekParameters.DEFAULT;
    }
    internalPlayer.setSeekParameters(seekParameters);
  }

  @Override
  public void stop() {
    stop(/* reset= */ false);
  }

  @Override
  public void stop(boolean reset) {
    PlaybackInfo playbackInfo =
        getResetPlaybackInfo(
            /* resetPosition= */ reset,
            /* resetState= */ reset,
            /* playbackState= */ Player.STATE_IDLE);
    // Trigger internal stop first before updating the playback info and notifying external
    // listeners to ensure that new operations issued in the listener notifications reach the
    // player after this stop. The internal player can't change the playback info immediately
    // because it uses a callback.
    pendingOperationAcks++;
    internalPlayer.stop(reset);
    updatePlaybackInfo(
        playbackInfo,
        /* positionDiscontinuity= */ false,
        /* ignored */ DISCONTINUITY_REASON_INTERNAL,
        TIMELINE_CHANGE_REASON_RESET,
        /* seekProcessed= */ false);
  }

  @Override
  public void release() {
    Log.i(TAG, "Release " + Integer.toHexString(System.identityHashCode(this)) + " ["
        + ExoPlayerLibraryInfo.VERSION_SLASHY + "] [" + Util.DEVICE_DEBUG_INFO + "] ["
        + ExoPlayerLibraryInfo.registeredModules() + "]");
    internalPlayer.release();
    eventHandler.removeCallbacksAndMessages(null);
  }

  @Override
  public void sendMessages(ExoPlayerMessage... messages) {
    for (ExoPlayerMessage message : messages) {
      createMessage(message.target).setType(message.messageType).setPayload(message.message).send();
    }
  }

  @Override
  public PlayerMessage createMessage(Target target) {
    return new PlayerMessage(
        internalPlayer,
        target,
        playbackInfo.timeline,
        getCurrentWindowIndex(),
        internalPlayerHandler);
  }

  @Override
  public void blockingSendMessages(ExoPlayerMessage... messages) {
    List<PlayerMessage> playerMessages = new ArrayList<>();
    for (ExoPlayerMessage message : messages) {
      playerMessages.add(
          createMessage(message.target)
              .setType(message.messageType)
              .setPayload(message.message)
              .send());
    }
    boolean wasInterrupted = false;
    for (PlayerMessage message : playerMessages) {
      boolean blockMessage = true;
      while (blockMessage) {
        try {
          message.blockUntilDelivered();
          blockMessage = false;
        } catch (InterruptedException e) {
          wasInterrupted = true;
        }
      }
    }
    if (wasInterrupted) {
      // Restore the interrupted status.
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public int getCurrentPeriodIndex() {
    if (shouldMaskPosition()) {
      return maskingPeriodIndex;
    } else {
      return playbackInfo.periodId.periodIndex;
    }
  }

  @Override
  public int getCurrentWindowIndex() {
    if (shouldMaskPosition()) {
      return maskingWindowIndex;
    } else {
      return playbackInfo.timeline.getPeriod(playbackInfo.periodId.periodIndex, period).windowIndex;
    }
  }

  @Override
  public int getNextWindowIndex() {
    Timeline timeline = playbackInfo.timeline;
    return timeline.isEmpty() ? C.INDEX_UNSET
        : timeline.getNextWindowIndex(getCurrentWindowIndex(), repeatMode, shuffleModeEnabled);
  }

  @Override
  public int getPreviousWindowIndex() {
    Timeline timeline = playbackInfo.timeline;
    return timeline.isEmpty() ? C.INDEX_UNSET
        : timeline.getPreviousWindowIndex(getCurrentWindowIndex(), repeatMode, shuffleModeEnabled);
  }

  @Override
  public long getDuration() {
    Timeline timeline = playbackInfo.timeline;
    if (timeline.isEmpty()) {
      return C.TIME_UNSET;
    }
    if (isPlayingAd()) {
      MediaPeriodId periodId = playbackInfo.periodId;
      timeline.getPeriod(periodId.periodIndex, period);
      long adDurationUs = period.getAdDurationUs(periodId.adGroupIndex, periodId.adIndexInAdGroup);
      return C.usToMs(adDurationUs);
    } else {
      return timeline.getWindow(getCurrentWindowIndex(), window).getDurationMs();
    }
  }

  @Override
  public long getCurrentPosition() {
    if (shouldMaskPosition()) {
      return maskingWindowPositionMs;
    } else {
      return playbackInfoPositionUsToWindowPositionMs(playbackInfo.positionUs);
    }
  }

  @Override
  public long getBufferedPosition() {
    // TODO - Implement this properly.
    if (shouldMaskPosition()) {
      return maskingWindowPositionMs;
    } else {
      return playbackInfoPositionUsToWindowPositionMs(playbackInfo.bufferedPositionUs);
    }
  }

  @Override
  public int getBufferedPercentage() {
    long position = getBufferedPosition();
    long duration = getDuration();
    return position == C.TIME_UNSET || duration == C.TIME_UNSET ? 0
        : (duration == 0 ? 100 : Util.constrainValue((int) ((position * 100) / duration), 0, 100));
  }

  @Override
  public boolean isCurrentWindowDynamic() {
    Timeline timeline = playbackInfo.timeline;
    return !timeline.isEmpty() && timeline.getWindow(getCurrentWindowIndex(), window).isDynamic;
  }

  @Override
  public boolean isCurrentWindowSeekable() {
    Timeline timeline = playbackInfo.timeline;
    return !timeline.isEmpty() && timeline.getWindow(getCurrentWindowIndex(), window).isSeekable;
  }

  @Override
  public boolean isPlayingAd() {
    return !shouldMaskPosition() && playbackInfo.periodId.isAd();
  }

  @Override
  public int getCurrentAdGroupIndex() {
    return isPlayingAd() ? playbackInfo.periodId.adGroupIndex : C.INDEX_UNSET;
  }

  @Override
  public int getCurrentAdIndexInAdGroup() {
    return isPlayingAd() ? playbackInfo.periodId.adIndexInAdGroup : C.INDEX_UNSET;
  }

  @Override
  public long getContentPosition() {
    if (isPlayingAd()) {
      playbackInfo.timeline.getPeriod(playbackInfo.periodId.periodIndex, period);
      return period.getPositionInWindowMs() + C.usToMs(playbackInfo.contentPositionUs);
    } else {
      return getCurrentPosition();
    }
  }

  @Override
  public int getRendererCount() {
    return renderers.length;
  }

  @Override
  public int getRendererType(int index) {
    return renderers[index].getTrackType();
  }

  @Override
  public TrackGroupArray getCurrentTrackGroups() {
    return playbackInfo.trackSelectorResult.groups;
  }

  @Override
  public TrackSelectionArray getCurrentTrackSelections() {
    return playbackInfo.trackSelectorResult.selections;
  }

  @Override
  public Timeline getCurrentTimeline() {
    return playbackInfo.timeline;
  }

  @Override
  public Object getCurrentManifest() {
    return playbackInfo.manifest;
  }

  // Not private so it can be called from an inner class without going through a thunk method.
  /* package */ void handleEvent(Message msg) {
    switch (msg.what) {
      case ExoPlayerImplInternal.MSG_PLAYBACK_INFO_CHANGED:
        handlePlaybackInfo(
            (PlaybackInfo) msg.obj,
            /* operationAcks= */ msg.arg1,
            /* positionDiscontinuity= */ msg.arg2 != C.INDEX_UNSET,
            /* positionDiscontinuityReason= */ msg.arg2);
        break;
      case ExoPlayerImplInternal.MSG_PLAYBACK_PARAMETERS_CHANGED:
        PlaybackParameters playbackParameters = (PlaybackParameters) msg.obj;
        if (!this.playbackParameters.equals(playbackParameters)) {
          this.playbackParameters = playbackParameters;
          for (Player.EventListener listener : listeners) {
            listener.onPlaybackParametersChanged(playbackParameters);
          }
        }
        break;
      case ExoPlayerImplInternal.MSG_ERROR:
        ExoPlaybackException exception = (ExoPlaybackException) msg.obj;
        for (Player.EventListener listener : listeners) {
          listener.onPlayerError(exception);
        }
        break;
      default:
        throw new IllegalStateException();
    }
  }

  private void handlePlaybackInfo(
      PlaybackInfo playbackInfo,
      int operationAcks,
      boolean positionDiscontinuity,
      @DiscontinuityReason int positionDiscontinuityReason) {
    pendingOperationAcks -= operationAcks;
    if (pendingOperationAcks == 0) {
      if (playbackInfo.startPositionUs == C.TIME_UNSET) {
        // Replace internal unset start position with externally visible start position of zero.
        playbackInfo =
            playbackInfo.fromNewPosition(
                playbackInfo.periodId, /* startPositionUs= */ 0, playbackInfo.contentPositionUs);
      }
      if ((!this.playbackInfo.timeline.isEmpty() || hasPendingPrepare)
          && playbackInfo.timeline.isEmpty()) {
        // Update the masking variables, which are used when the timeline becomes empty.
        maskingPeriodIndex = 0;
        maskingWindowIndex = 0;
        maskingWindowPositionMs = 0;
      }
      @Player.TimelineChangeReason
      int timelineChangeReason =
          hasPendingPrepare
              ? Player.TIMELINE_CHANGE_REASON_PREPARED
              : Player.TIMELINE_CHANGE_REASON_DYNAMIC;
      boolean seekProcessed = hasPendingSeek;
      hasPendingPrepare = false;
      hasPendingSeek = false;
      updatePlaybackInfo(
          playbackInfo,
          positionDiscontinuity,
          positionDiscontinuityReason,
          timelineChangeReason,
          seekProcessed);
    }
  }

  private PlaybackInfo getResetPlaybackInfo(
      boolean resetPosition, boolean resetState, int playbackState) {
    if (resetPosition) {
      maskingWindowIndex = 0;
      maskingPeriodIndex = 0;
      maskingWindowPositionMs = 0;
    } else {
      maskingWindowIndex = getCurrentWindowIndex();
      maskingPeriodIndex = getCurrentPeriodIndex();
      maskingWindowPositionMs = getCurrentPosition();
    }
    return new PlaybackInfo(
        resetState ? Timeline.EMPTY : playbackInfo.timeline,
        resetState ? null : playbackInfo.manifest,
        playbackInfo.periodId,
        playbackInfo.startPositionUs,
        playbackInfo.contentPositionUs,
        playbackState,
        /* isLoading= */ false,
        resetState ? emptyTrackSelectorResult : playbackInfo.trackSelectorResult);
  }

  private void updatePlaybackInfo(
      PlaybackInfo newPlaybackInfo,
      boolean positionDiscontinuity,
      @Player.DiscontinuityReason int positionDiscontinuityReason,
      @Player.TimelineChangeReason int timelineChangeReason,
      boolean seekProcessed) {
    boolean timelineOrManifestChanged =
        playbackInfo.timeline != newPlaybackInfo.timeline
            || playbackInfo.manifest != newPlaybackInfo.manifest;
    boolean playbackStateChanged = playbackInfo.playbackState != newPlaybackInfo.playbackState;
    boolean isLoadingChanged = playbackInfo.isLoading != newPlaybackInfo.isLoading;
    boolean trackSelectorResultChanged =
        this.playbackInfo.trackSelectorResult != newPlaybackInfo.trackSelectorResult;
    playbackInfo = newPlaybackInfo;
    if (timelineOrManifestChanged || timelineChangeReason == TIMELINE_CHANGE_REASON_PREPARED) {
      for (Player.EventListener listener : listeners) {
        listener.onTimelineChanged(
            playbackInfo.timeline, playbackInfo.manifest, timelineChangeReason);
      }
    }
    if (positionDiscontinuity) {
      for (Player.EventListener listener : listeners) {
        listener.onPositionDiscontinuity(positionDiscontinuityReason);
      }
    }
    if (trackSelectorResultChanged) {
      trackSelector.onSelectionActivated(playbackInfo.trackSelectorResult.info);
      for (Player.EventListener listener : listeners) {
        listener.onTracksChanged(
            playbackInfo.trackSelectorResult.groups, playbackInfo.trackSelectorResult.selections);
      }
    }
    if (isLoadingChanged) {
      for (Player.EventListener listener : listeners) {
        listener.onLoadingChanged(playbackInfo.isLoading);
      }
    }
    if (playbackStateChanged) {
      for (Player.EventListener listener : listeners) {
        listener.onPlayerStateChanged(playWhenReady, playbackInfo.playbackState);
      }
    }
    if (seekProcessed) {
      for (Player.EventListener listener : listeners) {
        listener.onSeekProcessed();
      }
    }
  }

  private long playbackInfoPositionUsToWindowPositionMs(long positionUs) {
    long positionMs = C.usToMs(positionUs);
    if (!playbackInfo.periodId.isAd()) {
      playbackInfo.timeline.getPeriod(playbackInfo.periodId.periodIndex, period);
      positionMs += period.getPositionInWindowMs();
    }
    return positionMs;
  }

  private boolean shouldMaskPosition() {
    return playbackInfo.timeline.isEmpty() || pendingOperationAcks > 0;
  }
}
