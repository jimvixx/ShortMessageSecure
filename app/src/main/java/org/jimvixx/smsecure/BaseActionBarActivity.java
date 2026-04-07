/*
 * Copyright (C) 2015 Whisper Systems
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

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.jimvixx.smsecure.util.DynamicLanguage;
import org.jimvixx.smsecure.util.DynamicTheme;
import org.jimvixx.smsecure.util.SMSecurePreferences;

public abstract class BaseActionBarActivity extends AppCompatActivity {

  private static final String TRANSITION_RECIPIENT_NAME = "recipient_name";

  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();
  private final DynamicTheme dynamicTheme = new DynamicTheme();

  private int getAppBackgroundColor() {
    TypedValue tv = new TypedValue();
    getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);

    return tv.resourceId != 0
            ? ContextCompat.getColor(this, tv.resourceId)
            : tv.data;
  }

  private void applySystemBarsColors() {
    boolean isNightMode = DynamicTheme.isNightMode(this);
    boolean lightSystemBars = !isNightMode;

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

    int bg = getAppBackgroundColor();
    window.setStatusBarColor(bg);
    window.setNavigationBarColor(bg);

    WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());

    controller.setAppearanceLightStatusBars(lightSystemBars);
    controller.setAppearanceLightNavigationBars(lightSystemBars);
  }

  private void applySystemBarsPadding() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

    final View content = findViewById(android.R.id.content);
    if (content == null) return;

    ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      Insets ime  = insets.getInsets(WindowInsetsCompat.Type.ime());

      int bottom = Math.max(bars.bottom, ime.bottom);

      v.setPadding(bars.left, bars.top, bars.right, bottom);

      return insets;
    });

    ViewCompat.requestApplyInsets(content);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);

    super.onCreate(savedInstanceState);

    applySystemBarsColors();
    applySystemBarsPadding();
  }

  @Override
  protected void onResume() {
    super.onResume();

    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);

    applyScreenshotSecurity(SMSecurePreferences.isScreenSecurityEnabled(this));
  }

  public final void applyScreenshotSecurity(boolean enabled) {
    if (enabled) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }

  protected final java.util.Locale getCurrentLocale() {
    return dynamicLanguage.getCurrentLocale();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return (keyCode == KeyEvent.KEYCODE_MENU && BaseActivity.isMenuWorkaroundRequired()) ||
            super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_MENU && BaseActivity.isMenuWorkaroundRequired()) {
      openOptionsMenu();
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  protected void startActivitySceneTransition(Intent intent, View sharedView) {
    Bundle bundle = ActivityOptionsCompat
            .makeSceneTransitionAnimation(this, sharedView, TRANSITION_RECIPIENT_NAME)
            .toBundle();

    startActivity(intent, bundle);
  }
}