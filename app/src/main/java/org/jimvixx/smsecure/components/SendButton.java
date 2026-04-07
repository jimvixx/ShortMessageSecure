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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.TransportOption;
import org.jimvixx.smsecure.util.ViewUtil;

public class SendButton extends AppCompatImageButton {

  public SendButton(@NonNull Context context) {
    super(context);
    initialize();
  }

  public SendButton(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public SendButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  private void initialize() {
    ViewUtil.mirrorIfRtl(this, getContext());

    if (isInEditMode()) {
      showPreviewState();
    }
  }

  private void showPreviewState() {
    setTransportIcon(R.drawable.ic_send_unlock);
    setTransportContentDescription(getResources().getString(R.string.Send));
  }

  public void setTransport(@NonNull TransportOption transportOption) {
    setTransportIcon(transportOption.getDrawable());
    setTransportContentDescription(transportOption.getDescription());
  }

  public void setTransportIcon(@DrawableRes int drawableRes) {
    setImageResource(drawableRes);
  }

  public void setTransportContentDescription(@Nullable CharSequence description) {
    setContentDescription(description);
  }

  public void setOnTransportLongClickListener(@Nullable View.OnLongClickListener listener) {
    setOnLongClickListener(listener);
  }

  public void setOnSendClickListener(@Nullable OnClickListener listener) {
    setOnClickListener(listener);
  }
}
