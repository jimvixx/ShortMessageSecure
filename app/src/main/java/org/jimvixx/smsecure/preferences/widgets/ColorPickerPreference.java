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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.TypedArrayUtils;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceViewHolder;

import com.takisoft.colorpicker.ColorPickerDialog;
import com.takisoft.colorpicker.ColorStateDrawable;

import org.jimvixx.smsecure.R;

public class ColorPickerPreference extends DialogPreference {

  private int[] colors;
  private CharSequence[] colorDescriptions;
  private int color;
  private int columns;
  private int size;
  private boolean sortColors;

  @Nullable
  private ImageView colorPreviewView;

  @Nullable
  private OnPreferenceChangeListener listener;

  public ColorPickerPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);

    @SuppressLint("AutoCloseable")
    TypedArray a = context.obtainStyledAttributes(
            attrs,
            R.styleable.ColorPickerPreference,
            defStyleAttr,
            defStyleRes
    );

    try {
      int colorsId = a.getResourceId(
              R.styleable.ColorPickerPreference_colors,
              R.array.color_picker_default_colors
      );

      if (colorsId != 0) {
        colors = context.getResources().getIntArray(colorsId);
      }

      colorDescriptions = a.getTextArray(R.styleable.ColorPickerPreference_colorDescriptions);
      color = a.getColor(R.styleable.ColorPickerPreference_currentColor, 0);
      columns = a.getInt(R.styleable.ColorPickerPreference_columns, 4);
      size = a.getInt(R.styleable.ColorPickerPreference_colorSize, 2);
      sortColors = a.getBoolean(R.styleable.ColorPickerPreference_sortColors, false);

    } finally {
      a.recycle();
    }

    initialize();
  }

  public ColorPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
    this(context, attrs, defStyleAttr, 0);
  }

  @SuppressLint("RestrictedApi")
  @SuppressWarnings("unused")
  public ColorPickerPreference(Context context, AttributeSet attrs) {
    this(context, attrs, TypedArrayUtils.getAttr(
            context,
            android.R.attr.dialogPreferenceStyle,
            android.R.attr.dialogPreferenceStyle
    ));
  }

  public ColorPickerPreference(Context context) {
    this(context, null);
  }

  private void initialize() {
    setLayoutResource(R.layout.smsecure_preference_color);
  }

  @Override
  public void setOnPreferenceChangeListener(OnPreferenceChangeListener listener) {
    super.setOnPreferenceChangeListener(listener);
    this.listener = listener;
  }

  @Override
  public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);

    colorPreviewView = (ImageView) holder.findViewById(R.id.color_preview);
    bindColorPreview();
    PreferenceEnabledStateBinder.bind(holder, this);
  }

  private void bindColorPreview() {
    if (colorPreviewView == null) {
      return;
    }

    Drawable[] colorDrawable = new Drawable[]{
            ContextCompat.getDrawable(getContext(), R.drawable.colorpickerpreference_pref_swatch)
    };

    colorPreviewView.setImageDrawable(new ColorStateDrawable(colorDrawable, color));
  }

  public int getColor() {
    return color;
  }

  public void setColor(int color) {
    setInternalColor(color, false);
  }

  public int[] getColors() {
    return colors;
  }

  public void setColors(int[] colors) {
    this.colors = colors;
  }

  public boolean isSortColors() {
    return sortColors;
  }

  public void setSortColors(boolean sortColors) {
    this.sortColors = sortColors;
  }

  public CharSequence[] getColorDescriptions() {
    return colorDescriptions;
  }

  public void setColorDescriptions(CharSequence[] colorDescriptions) {
    this.colorDescriptions = colorDescriptions;
  }

  public int getColumns() {
    return columns;
  }

  public void setColumns(int columns) {
    this.columns = columns;
  }

  @ColorPickerDialog.Size
  public int getSize() {
    return size;
  }

  public void setSize(@ColorPickerDialog.Size int size) {
    this.size = size;
  }

  private void setInternalColor(int color, boolean force) {
    int oldColor = getPersistedInt(0);
    boolean changed = oldColor != color;

    if (changed || force) {
      this.color = color;

      persistInt(color);
      bindColorPreview();

      if (listener != null) {
        listener.onPreferenceChange(this, color);
      }

      notifyChanged();
    }
  }

  @Override
  protected Object onGetDefaultValue(TypedArray a, int index) {
    return a.getString(index);
  }

  @Override
  protected void onSetInitialValue(@Nullable Object defaultValue) {
    int resolvedColor;

    if (defaultValue instanceof String && !TextUtils.isEmpty((String) defaultValue)) {
      resolvedColor = Color.parseColor((String) defaultValue);
    } else {
      resolvedColor = 0;
    }

    setInternalColor(getPersistedInt(resolvedColor), true);
  }
}
