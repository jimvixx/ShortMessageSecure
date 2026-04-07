/*
 * Copyright (C) 2011 Whisper Systems
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

package org.jimvixx.smsecure.contacts.avatars;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public final class AvatarDrawable extends Drawable {

  private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String letter;
  private final boolean drawText;

  public AvatarDrawable(@NonNull String letter, int bgColor, int textColor) {
    this.letter = !letter.isEmpty()
            ? letter.substring(0, 1).toUpperCase(Locale.getDefault())
            : "";
    this.drawText = !this.letter.trim().isEmpty();

    bgPaint.setColor(bgColor);

    textPaint.setColor(textColor);
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    Rect bounds = getBounds();
    float radius = Math.min(bounds.width(), bounds.height()) / 2f;

    canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, bgPaint);

    if (drawText) {
      textPaint.setTextSize(radius);
      Paint.FontMetrics fm = textPaint.getFontMetrics();
      float y = bounds.centerY() - (fm.ascent + fm.descent) / 2;

      canvas.drawText(letter, bounds.centerX(), y, textPaint);
    }
  }

  @Override
  public void setAlpha(int alpha) {
    bgPaint.setAlpha(alpha);
    textPaint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {
    bgPaint.setColorFilter(colorFilter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
