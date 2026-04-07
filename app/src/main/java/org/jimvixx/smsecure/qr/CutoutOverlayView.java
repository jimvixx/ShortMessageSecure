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

package org.jimvixx.smsecure.qr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Decorative overlay that darkens the whole screen except a rectangular "cutout".
 * The cutout coordinates must be in this view's coordinate system.
 */
public class CutoutOverlayView extends View {

  private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF cutout = new RectF(0, 0, 0, 0);

  public CutoutOverlayView(Context context) {
    super(context);
    init();
  }

  public CutoutOverlayView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public CutoutOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    // We need a software layer for CLEAR xfermode on many devices.
    setLayerType(LAYER_TYPE_SOFTWARE, null);

    dimPaint.setStyle(Paint.Style.FILL);

    dimPaint.setColor(0x40000000);

    clearPaint.setStyle(Paint.Style.FILL);
    clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
  }

  public void setCutout(int left, int top, int right, int bottom) {
    cutout.set(left, top, right, bottom);
    invalidate();
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    super.onDraw(canvas);

    canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

    canvas.drawRect(cutout, clearPaint);
  }
}
