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

import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.R;

public class EmojiSpan extends AnimatingImageSpan {
  private final int size;
  private final FontMetricsInt fm;

  public EmojiSpan(@NonNull Drawable drawable, @NonNull TextView tv) {
    super(drawable, tv);
    fm = tv.getPaint().getFontMetricsInt();
    size = fm != null ? Math.abs(fm.descent) + Math.abs(fm.ascent)
            : tv.getResources().getDimensionPixelSize(R.dimen.conversation_item_body_text_size);
    getDrawable().setBounds(0, 0, size, size);
  }

  @Override
  public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                     FontMetricsInt fm) {
    if (fm != null && this.fm != null) {
      fm.ascent = this.fm.ascent;
      fm.descent = this.fm.descent;
      fm.top = this.fm.top;
      fm.bottom = this.fm.bottom;
      return size;
    } else {
      return super.getSize(paint, text, start, end, fm);
    }
  }
}
