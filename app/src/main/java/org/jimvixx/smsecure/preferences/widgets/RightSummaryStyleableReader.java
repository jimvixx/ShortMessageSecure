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

package org.jimvixx.smsecure.preferences.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;

/**
 * Reads shared right-summary attributes from XML.
 */
public final class RightSummaryStyleableReader {

  private RightSummaryStyleableReader() {
  }

  public static RightSummaryConfig read(Context context, @Nullable AttributeSet attrs) {
    RightSummaryConfig config = new RightSummaryConfig();

    if (attrs == null) {
      return config;
    }

    TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SMSecureRightSummaryPreference);
    try {
      config.setRightSummary(a.getText(R.styleable.SMSecureRightSummaryPreference_rightSummary));
      config.setRightSummaryOn(a.getText(R.styleable.SMSecureRightSummaryPreference_rightSummaryOn));
      config.setRightSummaryOff(a.getText(R.styleable.SMSecureRightSummaryPreference_rightSummaryOff));
      config.setPasswordMask(a.getBoolean(
              R.styleable.SMSecureRightSummaryPreference_rightSummaryPasswordMask, false));

      CharSequence maskText = a.getText(
              R.styleable.SMSecureRightSummaryPreference_rightSummaryPasswordMaskText);
      if (maskText != null) {
        config.setPasswordMaskText(maskText);
      }

      config.setMaxLines(a.getInt(
              R.styleable.SMSecureRightSummaryPreference_rightSummaryMaxLines, 1));

      config.setHideWhenDisabled(a.getBoolean(
              R.styleable.SMSecureRightSummaryPreference_hideRightSummaryWhenDisabled, true));
    } finally {
      a.recycle();
    }

    return config;
  }
}