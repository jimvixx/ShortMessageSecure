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

package org.jimvixx.smsecure.components;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.TransportOption;
import org.jimvixx.smsecure.TransportOptions;
import org.jimvixx.smsecure.TransportOptionsPopup;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.List;

public class SendButtonController
        implements TransportOptions.OnTransportChangedListener,
        TransportOptionsPopup.SelectedListener,
        View.OnLongClickListener {

  private final Context context;
  private final SendButton sendButton;
  private final TransportOptions transportOptions;

  private Optional<TransportOptionsPopup> transportOptionsPopup = Optional.absent();

  private boolean forceSend;

  public SendButtonController(@NonNull Context context, @NonNull SendButton sendButton) {
    this.context          = context;
    this.sendButton       = sendButton;
    this.transportOptions = new TransportOptions(context);

    this.transportOptions.addOnTransportChangedListener(this);
    this.sendButton.setOnTransportLongClickListener(this);
    this.sendButton.setTransport(transportOptions.getSelectedTransport());
  }

  private TransportOptionsPopup getTransportOptionsPopup() {
    if (!transportOptionsPopup.isPresent()) {
      transportOptionsPopup = Optional.of(new TransportOptionsPopup(context, sendButton, this));
    }

    return transportOptionsPopup.get();
  }

  public boolean isManualSelection() {
    return transportOptions.isManualSelection();
  }

  public void addOnTransportChangedListener(@NonNull TransportOptions.OnTransportChangedListener listener) {
    transportOptions.addOnTransportChangedListener(listener);
  }

  @NonNull
  public TransportOption getSelectedTransport() {
    return transportOptions.getSelectedTransport();
  }

  public void resetAvailableTransports() {
    transportOptions.reset();
  }

  public void disableTransport(@NonNull TransportOption.Type type) {
    transportOptions.disableTransport(type);
  }

  public void disableTransport(@NonNull TransportOption.Type type, int subscriptionId) {
    transportOptions.disableTransport(type, subscriptionId);
  }

  public void disableTransport(@NonNull TransportOption.Type type, @NonNull List<Integer> subscriptionIds) {
    for (int subscriptionId : subscriptionIds) {
      transportOptions.disableTransport(type, subscriptionId);
    }
  }

  public void setDefaultTransport(@NonNull TransportOption.Type type) {
    transportOptions.setDefaultTransport(type);
  }

  public void setDefaultSubscriptionId(@NonNull Optional<Integer> subscriptionId) {
    transportOptions.setDefaultSubscriptionId(subscriptionId);
  }

  public boolean displayTransports(boolean forceSend) {
    if (transportOptions.getEnabledTransports().size() > 1) {
      getTransportOptionsPopup().display(transportOptions.getEnabledTransports());
      this.forceSend = forceSend;
      return true;
    }

    return false;
  }

  public boolean isForceSend() {
    if (forceSend) {
      forceSend = false;
      return true;
    }

    return false;
  }

  @Override
  public void onSelected(@NonNull TransportOption option) {
    transportOptions.setSelectedTransport(option);
    getTransportOptionsPopup().dismiss();
  }

  @Override
  public void onChange(@NonNull TransportOption newTransport, boolean isManualSelection) {
    sendButton.setTransport(newTransport);
  }

  @Override
  public boolean onLongClick(View v) {
    return displayTransports(false);
  }
}
