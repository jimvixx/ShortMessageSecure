package org.jimvixx.smsecure.preferences.widgets;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;

import org.jimvixx.smsecure.R;

/**
 * A custom PreferenceCategory used to keep section headers visually consistent.
 */
public class SMSecurePreferenceCategory extends PreferenceCategory {

  public SMSecurePreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  public SMSecurePreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  public SMSecurePreferenceCategory(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public SMSecurePreferenceCategory(Context context) {
    super(context);
    initialize();
  }

  private void initialize() {
    setLayoutResource(R.layout.smsecure_preference_category);
  }
}