/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

import static org.jimvixx.smsecure.TransportOption.Type;
import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.Manifest;
import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.util.CharacterCalculator;
import org.jimvixx.smsecure.util.DummyCharacterCalculator;
import org.jimvixx.smsecure.util.EncryptedSmsCharacterCalculator;
import org.jimvixx.smsecure.util.SmsCharacterCalculator;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.LinkedList;
import java.util.List;

public class TransportOptions {

  private final List<OnTransportChangedListener> listeners = new LinkedList<>();
  private final Context context;
  private final List<TransportOption> enabledTransports;

  private Type defaultTransportType = Type.INSECURE_SMS;
  private Optional<Integer> defaultSubscriptionId;
  private Optional<TransportOption> selectedOption = Optional.absent();

  public TransportOptions(Context context) {
    this.context = context;
    this.defaultSubscriptionId = SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(context);
    this.enabledTransports = initializeAvailableTransports();
  }

  public void reset() {
    List<TransportOption> transportOptions = initializeAvailableTransports();

    this.enabledTransports.clear();
    this.enabledTransports.addAll(transportOptions);

    if (selectedOption.isPresent() && !isEnabled(selectedOption.get())) {
      setSelectedTransport(null);
    } else {
      this.defaultTransportType = Type.INSECURE_SMS;
      this.defaultSubscriptionId = SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(context);

      notifyTransportChangeListeners();
    }
  }

  public void setDefaultTransport(Type type) {
    this.defaultTransportType = type;

    if (!selectedOption.isPresent()) {
      notifyTransportChangeListeners();
    }
  }

  public void setDefaultSubscriptionId(Optional<Integer> subscriptionId) {
    if (subscriptionId.isPresent() && subscriptionId.get() >= 0) {
      this.defaultSubscriptionId = subscriptionId;
    }

    if (!selectedOption.isPresent()) {
      notifyTransportChangeListeners();
    }
  }

  public boolean isManualSelection() {
    return this.selectedOption.isPresent();
  }

  public @NonNull TransportOption getSelectedTransport() {
    if (selectedOption.isPresent()) return selectedOption.get();

    if (defaultSubscriptionId.isPresent()) {
      for (TransportOption transportOption : enabledTransports) {
        if (transportOption.getType() == defaultTransportType &&
                (int) defaultSubscriptionId.get() == transportOption.getSimSubscriptionId().or(-1)) {
          return transportOption;
        }
      }
    }

    for (TransportOption transportOption : enabledTransports) {
      if (transportOption.getType() == defaultTransportType) {
        return transportOption;
      }
    }

    return getDefaultTransportOption();
  }

  public void setSelectedTransport(@Nullable TransportOption transportOption) {
    this.selectedOption = Optional.fromNullable(transportOption);
    notifyTransportChangeListeners();
  }

  public void disableTransport(Type type) {
    List<TransportOption> options = find(type);

    for (TransportOption option : options) {
      enabledTransports.remove(option);
      if (selectedOption.isPresent() && selectedOption.get().getType() == type) {
        setSelectedTransport(null);
      }
    }
  }

  public void disableTransport(Type type, int subscriptionId) {
    List<TransportOption> options = find(type);

    for (TransportOption option : options) {
      if (option.getSimSubscriptionId().or(-1) == subscriptionId) enabledTransports.remove(option);
      if (selectedOption.isPresent() && selectedOption.get().getType() == type && selectedOption.get().getSimSubscriptionId().or(-1) == subscriptionId) {
        setSelectedTransport(null);
      }
    }
  }

  public List<TransportOption> getEnabledTransports() {
    return enabledTransports;
  }

  public void addOnTransportChangedListener(OnTransportChangedListener listener) {
    this.listeners.add(listener);
  }

  private List<TransportOption> initializeAvailableTransports() {
    List<TransportOption> results = new LinkedList<>();

    results.addAll(getTransportOptionsForSimCards(Type.INSECURE_SMS, R.drawable.ic_send_unlock,
            resolveThemeColor(context, R.attr.transport_options__send_button_unsecure_background_color),
            context.getString(R.string.ConversationActivity_transport_insecure_sms),
            context.getString(R.string.conversation_activity__type_message_sms_insecure),
            new SmsCharacterCalculator()));
    results.addAll(getTransportOptionsForSimCards(Type.SECURE_SMS, R.drawable.ic_send_lock,
            resolveThemeColor(context, R.attr.transport_options__send_button_secure_background_color),
            context.getString(R.string.ConversationActivity_transport_secure_sms),
            context.getString(R.string.conversation_activity__type_message_sms_secure),
            new EncryptedSmsCharacterCalculator()));

    return results;
  }

  private @NonNull List<TransportOption> getTransportOptionsForSimCards(@NonNull Type type,
                                                                        @DrawableRes int drawable,
                                                                        int backgroundColor,
                                                                        @NonNull String text,
                                                                        @NonNull String composeHint,
                                                                        @NonNull CharacterCalculator characterCalculator) {
    List<TransportOption> results = new LinkedList<>();
    SubscriptionManagerCompat subscriptionManager = SubscriptionManagerCompat.from(context);
    List<SubscriptionInfoCompat> subscriptions;

    if (Permissions.hasAll(context, Manifest.permission.READ_PHONE_STATE)) {
      subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
    } else {
      subscriptions = new LinkedList<>();
    }

    for (SubscriptionInfoCompat subscriptionInfo : subscriptions) {
      results.add(new TransportOption(type,
              drawable,
              backgroundColor,
              text,
              composeHint,
              characterCalculator,
              Optional.of(subscriptionInfo.getDisplayName()),
              Optional.of(subscriptionInfo.getSubscriptionId())));
    }

    return results;
  }

  private void notifyTransportChangeListeners() {
    for (OnTransportChangedListener listener : listeners) {
      listener.onChange(getSelectedTransport(), selectedOption.isPresent());
    }
  }

  private List<TransportOption> find(Type type) {
    List<TransportOption> options = new LinkedList<>();
    for (TransportOption option : enabledTransports) {
      if (option.isType(type)) {
        options.add(option);
      }
    }
    return options;
  }

  private boolean isEnabled(TransportOption transportOption) {
    for (TransportOption option : enabledTransports) {
      if (option.equals(transportOption)) return true;
    }

    return false;
  }

  private TransportOption getDefaultTransportOption() {
    return new TransportOption(
            Type.DISABLED,
            R.drawable.ic_send_unlock,
            ContextCompat.getColor(context, R.color.grey_600),
            context.getString(R.string.TransportOptions_sms_disabled),
            context.getString(R.string.TransportOptions_no_sim_card_found),
            new DummyCharacterCalculator(),
            Optional.of(""),
            Optional.of(-1)
    );
  }

  public interface OnTransportChangedListener {
    void onChange(TransportOption newTransport, boolean manuallySelected);
  }
}
