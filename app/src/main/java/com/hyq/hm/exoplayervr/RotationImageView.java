package com.hyq.hm.exoplayervr;

import android.content.Context;
import android.graphics.Canvas;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Created by 海米 on 2018/11/1.
 */

public class RotationImageView extends ImageView {
    public RotationImageView(Context context) {
        super(context);
    }

    public RotationImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    private float rotate = 0;

    public void setRotate(float rotate) {
        this.rotate = rotate;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.rotate(rotate,getWidth()/2,getHeight()/2);
        super.onDraw(canvas);
        canvas.restore();
    }
}
