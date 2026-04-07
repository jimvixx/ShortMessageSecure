package org.jimvixx.smsecure.preferences.widgets;

import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

/**
 * Binds and configures a right-side summary view inside a custom preference layout.
 */
public final class PreferenceRightSummaryBinder {

  private PreferenceRightSummaryBinder() {
  }

  public static void bind(@Nullable TextView view,
                          @Nullable CharSequence text,
                          int maxLines,
                          boolean hideWhenDisabled,
                          @Nullable Preference preference) {
    if (view == null) return;

    final boolean enabled = preference == null || preference.isEnabled();
    final boolean shouldHide = TextUtils.isEmpty(text) || (hideWhenDisabled && !enabled);

    if (shouldHide) {
      view.setText(null);
      view.setVisibility(View.GONE);
      return;
    }

    final int safeMaxLines = Math.max(1, maxLines);

    view.setVisibility(View.VISIBLE);
    view.setEnabled(enabled);
    view.setText(text);
    view.setSingleLine(safeMaxLines == 1);
    view.setMaxLines(safeMaxLines);
    view.setEllipsize(TruncateAt.END);
  }
}