package com.google.android.exoplayer2.video;

import android.view.Surface;

/**
 * Created by 海米 on 2018/4/2.
 */

public interface VideoTimeListener {
    void onVideoTimeChanged(long time);
    Surface onSurface(Surface surface);
    void onSizeChanged(int width,int height);
    void onRelease();
    void onStart();
    void onStop();
}
