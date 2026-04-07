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
import android.graphics.Paint.FontMetricsInt;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;

import org.jimvixx.smsecure.components.emoji.EmojiProvider.EmojiDrawable;
import org.jimvixx.smsecure.util.ViewUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;

public class EmojiTextView extends AppCompatTextView {
  private CharSequence source;
  private boolean      needsEllipsizing;

  public EmojiTextView(Context context) {
    this(context, null);
  }

  public EmojiTextView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiTextView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override public void setText(@Nullable CharSequence text, BufferType type) {
    if (useSystemEmoji()) {
      super.setText(text, type);
      return;
    }
    source = EmojiProvider.getInstance(getContext()).emojify(text, this);
    setTextEllipsized(source);
  }

  private boolean useSystemEmoji() {
   return SMSecurePreferences.isSystemEmojiPreferred(getContext());
  }

  private void setTextEllipsized(final @Nullable CharSequence source) {
    super.setText(needsEllipsizing ? ViewUtil.ellipsize(source, this) : source, BufferType.SPANNABLE);
  }

  @Override public void invalidateDrawable(@NonNull Drawable drawable) {
    if (drawable instanceof EmojiDrawable) invalidate();
    else                                   super.invalidateDrawable(drawable);
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int size = MeasureSpec.getSize(widthMeasureSpec);
    final int mode = MeasureSpec.getMode(widthMeasureSpec);
    if (!useSystemEmoji()                                            &&
        getEllipsize() == TruncateAt.END                             &&
        !TextUtils.isEmpty(source)                                   &&
        (mode == MeasureSpec.AT_MOST || mode == MeasureSpec.EXACTLY) &&
        getPaint().breakText(source, 0, source.length()-1, true, size, null) != source.length())
    {
      needsEllipsizing = true;
      FontMetricsInt font = getPaint().getFontMetricsInt();
      super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(Math.abs(font.top - font.bottom), MeasureSpec.EXACTLY));
    } else {
      needsEllipsizing = false;
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
  }

  @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    if (changed && !useSystemEmoji()) setTextEllipsized(source);
    super.onLayout(changed, left, top, right, bottom);
  }
}
