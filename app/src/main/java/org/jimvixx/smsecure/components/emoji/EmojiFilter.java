/*
 * Copyright (C) 2015 Open Whisper Systems
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

import android.text.InputFilter;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.widget.TextView;

public class EmojiFilter implements InputFilter {
  private final TextView view;

  public EmojiFilter(TextView view) {
    this.view = view;
  }

  @Override
  public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend)
  {
    char[] v = new char[end - start];
    TextUtils.getChars(source, start, end, v, 0);

    Spannable emojified = EmojiProvider.getInstance(view.getContext()).emojify(new String(v), view);

    if (source instanceof Spanned && emojified != null) {
      TextUtils.copySpansFrom((Spanned) source, start, end, null, emojified, 0);
    }

    return emojified;
  }
}
