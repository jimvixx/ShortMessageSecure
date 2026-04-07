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

import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * Applies a consistent enabled/disabled visual state for custom preference layouts.
 */
public final class PreferenceEnabledStateBinder {

  private static final float ENABLED_ALPHA = 1.0f;
  private static final float DISABLED_ALPHA = 0.5f;

  private PreferenceEnabledStateBinder() {
  }

  public static void bind(@NonNull PreferenceViewHolder holder, @NonNull Preference preference) {
    View itemView = holder.itemView;
    boolean enabled = preference.isEnabled();

    itemView.setEnabled(enabled);
    itemView.setAlpha(enabled ? ENABLED_ALPHA : DISABLED_ALPHA);
  }
}
