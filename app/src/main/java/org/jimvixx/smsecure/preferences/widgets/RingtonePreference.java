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

package org.jimvixx.smsecure.preferences.widgets;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceViewHolder;

import org.jimvixx.smsecure.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

@SuppressWarnings({"WeakerAccess"})
public class RingtonePreference extends DialogPreference {

  private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
  private static final String RECIPIENT_RINGTONE_KEY = "pref_key_recipient_ringtone";

  private int ringtoneType = RingtoneManager.TYPE_RINGTONE;
  private boolean showDefault = true;
  private boolean showSilent = true;
  private boolean showAdd;

  @Nullable
  private Uri ringtoneUri;

  @Nullable
  private TextView rightSummaryView;

  @Nullable
  private CharSequence fixedLeftSummary;

  private boolean suppressSummaryIntercept;

  public RingtonePreference(@NonNull Context context,
                            @Nullable AttributeSet attrs,
                            int defStyleAttr,
                            int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    setLayoutResource(R.layout.smsecure_preference);

    if (attrs != null) {
      ringtoneType = attrs.getAttributeIntValue(
              ANDROID_NS,
              "ringtoneType",
              RingtoneManager.TYPE_RINGTONE
      );
      showDefault = attrs.getAttributeBooleanValue(ANDROID_NS, "showDefault", true);
      showSilent = attrs.getAttributeBooleanValue(ANDROID_NS, "showSilent", true);
    }

    TypedArray typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.RingtonePreference,
            defStyleAttr,
            0
    );
    try {
      showAdd = typedArray.getBoolean(R.styleable.RingtonePreference_showAdd, true);
    } finally {
      typedArray.recycle();
    }

    fixedLeftSummary = super.getSummary();
  }

  public RingtonePreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    this(context, attrs, defStyleAttr, 0);
  }

  public RingtonePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, android.R.attr.dialogPreferenceStyle);
  }

  public RingtonePreference(@NonNull Context context) {
    this(context, null);
  }

  private static boolean equalsNullable(@Nullable Object first, @Nullable Object second) {
    return Objects.equals(first, second);
  }

  @Override
  public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);

    rightSummaryView = (TextView) holder.findViewById(R.id.right_summary);
    updateRightSummary();
    PreferenceEnabledStateBinder.bind(holder, this);
  }

  @Override
  public void setSummary(CharSequence summary) {
    if (suppressSummaryIntercept) {
      super.setSummary(summary);
      return;
    }

    if (fixedLeftSummary == null) {
      fixedLeftSummary = summary;
      super.setSummary(summary);
      return;
    }

    if (rightSummaryView != null) {
      if (TextUtils.isEmpty(summary)) {
        rightSummaryView.setText(null);
        rightSummaryView.setVisibility(View.GONE);
      } else {
        rightSummaryView.setText(summary);
        rightSummaryView.setVisibility(View.VISIBLE);
      }
    }

    suppressSummaryIntercept = true;
    try {
      super.setSummary(fixedLeftSummary);
    } finally {
      suppressSummaryIntercept = false;
    }
  }

  private void updateRightSummary() {
    if (rightSummaryView == null) return;

    Uri uri = onRestoreRingtone();

    if (uri == null) {
      rightSummaryView.setText(R.string.Silent);
      rightSummaryView.setVisibility(View.VISIBLE);
      return;
    }

    String title = getRingtoneTitle();
    rightSummaryView.setText(!TextUtils.isEmpty(title)
            ? title
            : getContext().getString(R.string.RingtonePreference_system_default));
    rightSummaryView.setVisibility(View.VISIBLE);
  }

  @RingtoneType
  public int getRingtoneType() {
    return ringtoneType;
  }

  public void setRingtoneType(@RingtoneType int ringtoneType) {
    this.ringtoneType = ringtoneType;
  }

  public boolean isShowDefault() {
    return showDefault;
  }

  public void setShowDefault(boolean showDefault) {
    this.showDefault = showDefault;
  }

  public boolean isShowSilent() {
    return showSilent;
  }

  public void setShowSilent(boolean showSilent) {
    this.showSilent = showSilent;
  }

  public boolean isShowAdd() {
    return showAdd;
  }

  public void setShowAdd(boolean showAdd) {
    this.showAdd = showAdd;
  }

  boolean shouldShowAdd() {
    if (!showAdd) return false;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return true;
    }

    try {
      Context context = getContext();
      PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
              context.getPackageName(),
              PackageManager.GET_PERMISSIONS
      );

      String[] permissions = packageInfo.requestedPermissions;
      if (permissions == null) return false;

      for (String permission : permissions) {
        if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
          return true;
        }
      }
    } catch (Exception ignore) {
    }

    return false;
  }

  @Nullable
  public Uri getRingtone() {
    return onRestoreRingtone();
  }

  public void setRingtone(@Nullable Uri uri) {
    setInternalRingtone(uri, false);
  }

  private void setInternalRingtone(@Nullable Uri uri, boolean force) {
    Uri oldUri = onRestoreRingtone();
    boolean changed = !equalsNullable(oldUri, uri);

    if (!changed && !force) {
      return;
    }

    boolean wasBlocking = shouldDisableDependents();

    ringtoneUri = uri;
    onSaveRingtone(uri);

    boolean isBlocking = shouldDisableDependents();
    notifyChanged();
    updateRightSummary();

    if (isBlocking != wasBlocking) {
      notifyDependencyChange(isBlocking);
    }
  }

  protected void onSaveRingtone(@Nullable Uri ringtoneUri) {
    persistString(ringtoneUri != null ? ringtoneUri.toString() : "");
  }

  @Nullable
  protected Uri onRestoreRingtone() {
    String persisted = getPersistedString(ringtoneUri != null ? ringtoneUri.toString() : null);
    return TextUtils.isEmpty(persisted) ? null : Uri.parse(persisted);
  }

  @Override
  protected Object onGetDefaultValue(@NonNull TypedArray a, int index) {
    return a.getString(index);
  }

  @Override
  protected void onSetInitialValue(Object defaultValue) {
    String defaultString = defaultValue instanceof String ? (String) defaultValue : null;

    Uri value;
    if (getPersistedString(null) != null) {
      value = onRestoreRingtone();
    } else if (!TextUtils.isEmpty(defaultString)) {
      value = Uri.parse(defaultString);
    } else {
      value = null;
    }

    setInternalRingtone(value, true);
  }

  @Override
  public boolean shouldDisableDependents() {
    return super.shouldDisableDependents() || onRestoreRingtone() == null;
  }

  private boolean isRecipientRingtonePreference() {
    return RECIPIENT_RINGTONE_KEY.equals(getKey());
  }

  @Nullable
  public String getRingtoneTitle() {
    Uri uri = onRestoreRingtone();
    if (uri == null) return null;

    Context context = getContext();

    if (RingtoneManager.getDefaultType(uri) != -1) {
      if (isRecipientRingtonePreference()
              && Settings.System.DEFAULT_NOTIFICATION_URI.equals(uri)) {
        return context.getString(R.string.Default);
      }

      return context.getString(R.string.RingtonePreference_system_default);
    }

    ContentResolver contentResolver = context.getContentResolver();
    String[] projection = {MediaStore.MediaColumns.TITLE};

    try (Cursor cursor = contentResolver.query(uri, projection, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getString(0);
      }
    } catch (Exception ignore) {
    }

    return null;
  }

  @IntDef({
          RingtoneManager.TYPE_ALL,
          RingtoneManager.TYPE_ALARM,
          RingtoneManager.TYPE_NOTIFICATION,
          RingtoneManager.TYPE_RINGTONE
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface RingtoneType {
  }
}