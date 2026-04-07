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

package org.jimvixx.smsecure.components.reminder;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.ViewUtil;

/**
 * View to display actionable reminders to the user
 */
public class ReminderView extends LinearLayout {
  private ViewGroup   container;
  private TextView    acceptButton;
  private TextView    closeButton;
  private TextView    title;
  private TextView    text;

  public ReminderView(Context context) {
    super(context);
    initialize();
  }

  public ReminderView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public ReminderView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  private void initialize() {
    LayoutInflater.from(getContext()).inflate(R.layout.reminder_header, this, true);
    container    = ViewUtil.findById(this, R.id.container);
    acceptButton = ViewUtil.findById(this, R.id.accept);
    closeButton  = ViewUtil.findById(this, R.id.cancel);
    title        = ViewUtil.findById(this, R.id.reminder_title);
    text         = ViewUtil.findById(this, R.id.reminder_text);
  }

  public void showReminder(final Reminder reminder) {
    title.setText(reminder.getTitle());
    text.setText(reminder.getText());
    acceptButton.setText(reminder.getButtonText());

    acceptButton.setOnClickListener(v -> {
      hide();
      if (reminder.getOkListener() != null) reminder.getOkListener().onClick(v);
    });

    if (reminder.isDismissable()) {
      closeButton.setOnClickListener(v -> {
        hide();
        if (reminder.getDismissListener() != null) reminder.getDismissListener().onClick(v);
      });
    } else {
      closeButton.setVisibility(View.GONE);
    }

    container.setVisibility(View.VISIBLE);
  }

  public void requestDismiss() {
    closeButton.performClick();
  }

  public void hide() {
    container.setVisibility(View.GONE);
  }
}
