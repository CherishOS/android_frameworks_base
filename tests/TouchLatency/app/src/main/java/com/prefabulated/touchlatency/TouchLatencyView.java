/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.prefabulated.touchlatency;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Trace;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import java.math.RoundingMode;
import java.text.DecimalFormat;

class TouchLatencyView extends View implements View.OnTouchListener {
    private static final String LOG_TAG = "TouchLatency";
    private static final int BACKGROUND_COLOR = 0xFF400080;
    private static final int INNER_RADIUS = 70;
    private static final int BALL_DIAMETER = 200;
    private static final int SEC_TO_NANOS = 1000000000;
    private static final float FPS_UPDATE_THRESHOLD = 20;
    private static final long BALL_VELOCITY = 420;

    public TouchLatencyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Trace.beginSection("TouchLatencyView constructor");
        setOnTouchListener(this);
        setWillNotDraw(false);
        mBluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBluePaint.setColor(0xFF0000FF);
        mBluePaint.setStyle(Paint.Style.FILL);
        mGreenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGreenPaint.setColor(0xFF00FF00);
        mGreenPaint.setStyle(Paint.Style.FILL);
        mYellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mYellowPaint.setColor(0xFFFFFF00);
        mYellowPaint.setStyle(Paint.Style.FILL);
        mRedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRedPaint.setColor(0xFFFF0000);
        mRedPaint.setStyle(Paint.Style.FILL);
        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(0xFFFFFFFF);
        mTextPaint.setTextSize(100);
        mTextPaint.setTextAlign(Paint.Align.RIGHT);

        mTouching = false;

        mLastDrawNano = 0;
        mFps = 0;
        mLastFpsUpdate = 0;
        mFrameCount = 0;

        mDf = new DecimalFormat("fps: #.##");
        mDf.setRoundingMode(RoundingMode.HALF_UP);

        Trace.endSection();
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        Trace.beginSection("TouchLatencyView onTouch");
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            mTouching = true;
            invalidate();

            mTouchX = event.getX();
            mTouchY = event.getY();
        } else if (action == MotionEvent.ACTION_UP) {
            mTouching = false;
            invalidate();
        }
        Trace.endSection();
        return true;
    }

    private void drawTouch(Canvas canvas) {
        Trace.beginSection("TouchLatencyView drawTouch");

        try {
            if (!mTouching) {
                Log.d(LOG_TAG, "Filling background");
                canvas.drawColor(BACKGROUND_COLOR);
                return;
            }

            float deltaX = (mTouchX - mLastDrawnX);
            float deltaY = (mTouchY - mLastDrawnY);
            float scaleFactor = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY) * 1.5f;

            mLastDrawnX = mTouchX;
            mLastDrawnY = mTouchY;

            canvas.drawColor(BACKGROUND_COLOR);
            canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS + 3 * scaleFactor, mRedPaint);
            canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS + 2 * scaleFactor, mYellowPaint);
            canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS + scaleFactor, mGreenPaint);
            canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS, mBluePaint);
        } finally {
            Trace.endSection();
        }
    }

    private Paint getBallColor() {
        if (mFps > 75) {
            return mGreenPaint;
        } else if (mFps > 45) {
            return mYellowPaint;
        } else
            return mRedPaint;
    }

    private void drawBall(Canvas canvas) {
        Trace.beginSection("TouchLatencyView drawBall");
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        float fps = 0f;

        long t = System.nanoTime();
        long tDiff = t - mLastDrawNano;
        mLastDrawNano = t;
        mFrameCount++;

        if (tDiff < SEC_TO_NANOS) {
            fps = 1f * SEC_TO_NANOS / tDiff;
        }

        long fDiff = t - mLastFpsUpdate;
        if (Math.abs(mFps - fps) > FPS_UPDATE_THRESHOLD) {
            mFps = fps;
            mLastFpsUpdate = t;
            mFrameCount = 0;
        } else if (fDiff > SEC_TO_NANOS) {
            mFps = 1f * mFrameCount * SEC_TO_NANOS / fDiff;
            mLastFpsUpdate = t;
            mFrameCount = 0;
        }

        final long pos = t * BALL_VELOCITY / SEC_TO_NANOS;
        final long xMax = width - BALL_DIAMETER;
        final long yMax = height - BALL_DIAMETER;
        long xOffset = pos % xMax;
        long yOffset = pos % yMax;

        float left, right, top, bottom;

        if (((pos / xMax) & 1) == 0) {
            left = xMax - xOffset;
        } else {
            left = xOffset;
        }
        right = left + BALL_DIAMETER;

        if (((pos / yMax) & 1) == 0) {
            top = yMax - yOffset;
        } else {
            top = yOffset;
        }
        bottom = top + BALL_DIAMETER;

        // Draw the ball
        canvas.drawColor(BACKGROUND_COLOR);
        canvas.drawOval(left, top, right, bottom, getBallColor());
        canvas.drawText(mDf.format(mFps), width, 100, mTextPaint);

        invalidate();
        Trace.endSection();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Trace.beginSection("TouchLatencyView onDraw");
        if (mMode == 0) {
            drawTouch(canvas);
        } else {
            drawBall(canvas);
        }
        Trace.endSection();
    }

    public void changeMode(MenuItem item) {
        Trace.beginSection("TouchLatencyView changeMode");
        final int NUM_MODES = 2;
        final String modes[] = {"Touch", "Ball"};
        mMode = (mMode + 1) % NUM_MODES;
        invalidate();
        item.setTitle(modes[mMode]);
        Trace.endSection();
    }

    private final Paint mBluePaint, mGreenPaint, mYellowPaint, mRedPaint, mTextPaint;
    private int mMode;

    private boolean mTouching;
    private float mTouchX, mTouchY;
    private float mLastDrawnX, mLastDrawnY;

    private long mLastDrawNano, mLastFpsUpdate, mFrameCount;
    private float mFps;
    private DecimalFormat mDf;
}
