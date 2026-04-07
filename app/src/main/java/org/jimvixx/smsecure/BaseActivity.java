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

package org.jimvixx.smsecure;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;

public abstract class BaseActivity extends FragmentActivity {

  public static boolean isMenuWorkaroundRequired() {
    return false;
  }

  private void applySystemBarsPadding() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

    final View content = findViewById(android.R.id.content);
    if (content == null) return;

    ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);

      return insets;
    });

    ViewCompat.requestApplyInsets(content);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
    applySystemBarsPadding();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return (keyCode == KeyEvent.KEYCODE_MENU && isMenuWorkaroundRequired()) || super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_MENU && isMenuWorkaroundRequired()) {
      openOptionsMenu();
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }
}
