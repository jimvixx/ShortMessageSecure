/*
 * Copyright (C) 2026 Jimvixx
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

package org.jimvixx.smsecure.search;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.Locale;

public final class SearchHighlightUtil {
  private SearchHighlightUtil() {}

  public static @NonNull CharSequence highlight(@NonNull CharSequence text,
                                                 @NonNull String query,
                                                 @ColorInt int color) {
    SpannableString result = new SpannableString(text);
    String needle = query.trim().toLowerCase(Locale.ROOT);
    if (needle.isEmpty()) return result;

    String haystack = text.toString().toLowerCase(Locale.ROOT);
    int start = 0;
    while ((start = haystack.indexOf(needle, start)) >= 0) {
      int end = start + needle.length();
      result.setSpan(new ForegroundColorSpan(color), start, end,
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      start = end;
    }
    return result;
  }

  public static @NonNull String excerptAroundMatch(@NonNull String text,
                                                    @NonNull String query) {
    String needle = query.trim().toLowerCase(Locale.ROOT);
    if (needle.isEmpty()) return text;

    int matchStart = text.toLowerCase(Locale.ROOT).indexOf(needle);
    if (matchStart < 0) return text;

    // Keep the match close to the leading edge so it remains visible when the
    // one-line result is ellipsized on narrow portrait screens.
    int start = Math.max(0, matchStart - 12);
    int end = Math.min(text.length(), matchStart + needle.length() + 90);
    String excerpt = text.substring(start, end);
    return (start > 0 ? "…" : "") + excerpt + (end < text.length() ? "…" : "");
  }
}
