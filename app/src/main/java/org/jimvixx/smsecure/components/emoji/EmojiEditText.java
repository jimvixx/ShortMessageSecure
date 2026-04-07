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
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputFilter;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import org.jimvixx.smsecure.components.emoji.EmojiProvider.EmojiDrawable;
import org.jimvixx.smsecure.util.SMSecurePreferences;


public class EmojiEditText extends AppCompatEditText {
  @SuppressWarnings("unused")
  private static final String TAG = EmojiEditText.class.getSimpleName();

  public EmojiEditText(Context context) {
    this(context, null);
  }

  public EmojiEditText(Context context, AttributeSet attrs) {
    this(context, attrs, androidx.appcompat.R.attr.editTextStyle);
  }

  public EmojiEditText(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    if (!SMSecurePreferences.isSystemEmojiPreferred(getContext())) {
      setFilters(new InputFilter[]{new EmojiFilter(this)});
    }
  }

  public void insertEmoji(@NonNull String emoji) {
    Editable text = getText();
    if (text == null || !isFocused()) return;

    int start = getSelectionStart();
    int end = getSelectionEnd();

    if (start < 0 || end < 0) {
      text.append(emoji);
      setSelection(text.length());
      return;
    }

    int min = Math.min(start, end);
    int max = Math.max(start, end);

    text.replace(min, max, emoji);
    setSelection(min + emoji.length());
  }

  @Override
  public void invalidateDrawable(@NonNull Drawable drawable) {
    if (drawable instanceof EmojiDrawable) invalidate();
    else super.invalidateDrawable(drawable);
  }

  private InputFilter[] appendEmojiFilter(@Nullable InputFilter[] originalFilters) {
    InputFilter[] result;

    if (originalFilters != null) {
      result = new InputFilter[originalFilters.length + 1];
      System.arraycopy(originalFilters, 0, result, 1, originalFilters.length);
    } else {
      result = new InputFilter[1];
    }

    result[0] = new EmojiFilter(this);

    return result;
  }
}
