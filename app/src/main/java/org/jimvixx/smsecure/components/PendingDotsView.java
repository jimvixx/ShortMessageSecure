/*
 * Copyright (C) 2025 Jimvixx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jimvixx.smsecure.components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

public class PendingDotsView extends View {

  private static final int DOT_COUNT = 3;
  private static final long ANIMATION_DURATION_MS = 900L;

  private static final float MIN_ALPHA = 0.35f;
  private static final float MAX_ALPHA = 1.00f;

  private static final float MIN_SCALE = 0.80f;
  private static final float MAX_SCALE = 1.10f;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private float baseRadius;
  private float centerSpacing;
  private float phase;

  @Nullable
  private ValueAnimator animator;

  public PendingDotsView(Context context) {
    this(context, null);
  }

  public PendingDotsView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public PendingDotsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    float density = getResources().getDisplayMetrics().density;

    this.baseRadius = 1.5f * density;
    this.centerSpacing = 6.0f * density;

    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xFF888888);

    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  public void setDotColor(@ColorInt int color) {
    paint.setColor(color);
    invalidate();
  }

  public void start() {
    if (animator != null && animator.isRunning()) {
      return;
    }

    animator = ValueAnimator.ofFloat(0f, 1f);
    animator.setDuration(ANIMATION_DURATION_MS);
    animator.setRepeatCount(ValueAnimator.INFINITE);
    animator.setInterpolator(new LinearInterpolator());
    animator.addUpdateListener(animation -> {
      phase = (float) animation.getAnimatedValue();
      invalidate();
    });
    animator.start();
  }

  public void stop() {
    if (animator != null) {
      animator.cancel();
      animator = null;
    }

    phase = 0f;
    invalidate();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();

    if (getVisibility() == VISIBLE) {
      start();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    stop();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);

    if (visibility == VISIBLE) {
      start();
    } else {
      stop();
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    float maxRadius = baseRadius * MAX_SCALE;

    int desiredWidth = (int) Math.ceil(
            getPaddingLeft()
                    + getPaddingRight()
                    + (maxRadius * 2f)
                    + ((DOT_COUNT - 1) * centerSpacing)
    );

    int desiredHeight = (int) Math.ceil(
            getPaddingTop()
                    + getPaddingBottom()
                    + (maxRadius * 2f)
    );

    int measuredWidth = resolveSize(desiredWidth, widthMeasureSpec);
    int measuredHeight = resolveSize(desiredHeight, heightMeasureSpec);

    setMeasuredDimension(measuredWidth, measuredHeight);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    float centerY = getPaddingTop()
            + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;

    float contentWidth = (baseRadius * 2f) + ((DOT_COUNT - 1) * centerSpacing);
    float startX = getPaddingLeft()
            + (getWidth() - getPaddingLeft() - getPaddingRight() - contentWidth) / 2f
            + baseRadius;

    for (int i = 0; i < DOT_COUNT; i++) {
      float localPhase = (phase + i / (float) DOT_COUNT) % 1f;
      float pulse = triangle(localPhase);

      float alpha = MIN_ALPHA + pulse * (MAX_ALPHA - MIN_ALPHA);
      float scale = MIN_SCALE + pulse * (MAX_SCALE - MIN_SCALE);
      float radius = baseRadius * scale;

      paint.setAlpha(Math.round(alpha * 255f));

      float cx = startX + i * centerSpacing;
      canvas.drawCircle(cx, centerY, radius, paint);
    }

    paint.setAlpha(255);
  }

  private float triangle(float value) {
    if (value < 0.5f) {
      return value / 0.5f;
    } else {
      return 1f - ((value - 0.5f) / 0.5f);
    }
  }
}