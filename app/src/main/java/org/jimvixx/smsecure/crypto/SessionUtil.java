package org.jimvixx.smsecure.crypto;

import android.content.Context;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.crypto.storage.SMSecureSessionStore;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionStore;

import java.util.LinkedList;
import java.util.List;

public class SessionUtil {

  public static boolean hasSession(Context context, MasterSecret masterSecret, @NonNull String number, int subscriptionId) {
    SessionStore sessionStore = new SMSecureSessionStore(context, masterSecret, subscriptionId);
    SignalProtocolAddress axolotlAddress = new SignalProtocolAddress(number, 1);

    return sessionStore.containsSession(axolotlAddress);
  }

  public static boolean hasSession(Context context, MasterSecret masterSecret, @NonNull String number, List<SubscriptionInfoCompat> activeSubscriptions) {
    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      if (!hasSession(context, masterSecret, number, subscriptionInfo.getSubscriptionId()))
        return false;
    }
    return true;
  }

  public static boolean hasAtLeastOneSession(Context context, MasterSecret masterSecret, @NonNull String number, List<SubscriptionInfoCompat> activeSubscriptions) {
    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      if (hasSession(context, masterSecret, number, subscriptionInfo.getSubscriptionId()))
        return true;
    }
    return false;
  }

  public static List<Integer> getSubscriptionIdWithoutSession(Context context, MasterSecret masterSecret, @NonNull String number, List<SubscriptionInfoCompat> activeSubscriptions) {
    LinkedList<Integer> list = new LinkedList<>();

    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      int subscriptionId = subscriptionInfo.getSubscriptionId();
      if (!hasSession(context, masterSecret, number, subscriptionId)) list.add(subscriptionId);
    }
    return list;
  }
}
