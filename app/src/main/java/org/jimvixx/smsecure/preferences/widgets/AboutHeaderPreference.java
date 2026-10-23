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

package org.jimvixx.smsecure.preferences.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.R;

public final class AboutHeaderPreference extends Preference {

  public AboutHeaderPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public AboutHeaderPreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  public AboutHeaderPreference(Context context, AttributeSet attrs,
                              int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  private void initialize() {
    setLayoutResource(R.layout.about_header_preference);
  }

  @Override
  public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);

    TextView version = (TextView) holder.findViewById(R.id.about_version);
    if (version != null) {
      version.setText(getContext().getString(
              R.string.preferences__about_version,
              BuildConfig.VERSION_NAME
      ));
    }
  }
}
