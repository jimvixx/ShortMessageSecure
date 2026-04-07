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

package org.jimvixx.smsecure.components;

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.R;

public class AlertView extends LinearLayout {

  @SuppressWarnings("unused")
  private static final String TAG = AlertView.class.getSimpleName();

  private ImageView approvalIndicator;
  private ImageView failedIndicator;

  public AlertView(Context context) {
    this(context, null);
  }

  public AlertView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize(attrs);
  }

  public AlertView(final Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    initialize(attrs);
  }

  @SuppressWarnings("resource")
  private void initialize(AttributeSet attrs) {
    inflate(getContext(), R.layout.alert_view, this);

    approvalIndicator = findViewById(R.id.pending_approval_indicator);
    failedIndicator = findViewById(R.id.sms_failed_indicator);

    if (attrs != null) {
      TypedArray a = getContext()
              .getTheme()
              .obtainStyledAttributes(attrs, R.styleable.AlertView, 0, 0);
      try {
        boolean useSmallIcon = a.getBoolean(R.styleable.AlertView_useSmallIcon, false);
        if (useSmallIcon) {
          failedIndicator.setImageDrawable(
                  ContextCompat.getDrawable(getContext(), R.drawable.ic_information)
          );
          failedIndicator.setImageTintList(
                  android.content.res.ColorStateList.valueOf(
                          resolveThemeColor(getContext(), R.attr.appColorCommonAlert)
                  )
          );

        }
      } finally {
        a.recycle();
      }
    }
  }

  public void setNone() {
    this.setVisibility(View.GONE);
  }

  public void setPendingApproval() {
    this.setVisibility(View.VISIBLE);
    approvalIndicator.setVisibility(View.VISIBLE);
    failedIndicator.setVisibility(View.GONE);
  }

  public void setFailed() {
    this.setVisibility(View.VISIBLE);
    approvalIndicator.setVisibility(View.GONE);
    failedIndicator.setVisibility(View.VISIBLE);
  }
}
