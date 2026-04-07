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

package org.jimvixx.smsecure.components.emoji;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.ResUtil;

public class EmojiView extends View implements Drawable.Callback {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private String emoji;
  private Drawable drawable;

  public EmojiView(Context context) {
    this(context, null);
  }

  public EmojiView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public String getEmoji() {
    return emoji;
  }

  public void setEmoji(String emoji) {
    this.emoji = emoji;
    this.drawable = EmojiProvider.getInstance(getContext())
            .getEmojiDrawable(emoji);
    postInvalidate();
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    super.onDraw(canvas);

    final int width = getWidth();
    final int height = getHeight();

    if (drawable != null) {
      drawable.setBounds(
              getPaddingLeft(),
              getPaddingTop(),
              width - getPaddingRight(),
              height - getPaddingBottom()
      );
      drawable.setCallback(this);
      drawable.draw(canvas);
      return;
    }

    if (emoji == null) return;

    final float contentWidth = width - getPaddingLeft() - getPaddingRight();
    final float contentHeight = height - getPaddingTop() - getPaddingBottom();

    if (contentWidth <= 0f || contentHeight <= 0f) return;

    float targetFontSize = 0.75f * contentHeight;
    paint.setTextSize(targetFontSize);
    paint.setColor(ResUtil.getColor(getContext(), R.attr.appColorTextPrimary));
    paint.setTextAlign(Paint.Align.CENTER);

    float x = width * 0.5f;
    float y = height * 0.5f - (paint.descent() + paint.ascent()) * 0.5f;

    float textWidth = paint.measureText(emoji);
    float overflow = textWidth / contentWidth;

    if (overflow > 1f) {
      paint.setTextSize(targetFontSize / overflow);
      y = height * 0.5f - (paint.descent() + paint.ascent()) * 0.5f;
    }

    canvas.drawText(emoji, x, y, paint);
  }

  @Override
  public void invalidateDrawable(@NonNull Drawable drawable) {
    super.invalidateDrawable(drawable);
    postInvalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }
}
