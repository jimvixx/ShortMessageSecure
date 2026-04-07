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

package org.jimvixx.smsecure.recipients;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.jimvixx.smsecure.logging.Log;
import android.util.Patterns;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.contacts.avatars.ContactColors;
import org.jimvixx.smsecure.contacts.avatars.ContactPhoto;
import org.jimvixx.smsecure.contacts.avatars.ContactPhotoFactory;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase.VibrateState;
import org.jimvixx.smsecure.recipients.Recipient.RecipientModifiedListener;
import org.jimvixx.smsecure.util.FutureTaskListener;
import org.jimvixx.smsecure.util.ListenableFutureTask;
import org.jimvixx.smsecure.util.NumberUtil;
import org.jimvixx.smsecure.util.Util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public class Recipients implements Iterable<Recipient>, RecipientModifiedListener {

  private static final String TAG = Recipients.class.getSimpleName();

  private final Set<RecipientsModifiedListener> listeners = Collections.newSetFromMap(new WeakHashMap<>());
  private final List<Recipient> recipients;

  private Uri          ringtone              = null;
  private long         mutedUntil            = 0;
  private boolean      blocked               = false;
  private VibrateState vibrate               = VibrateState.DEFAULT;
  private boolean      stale                 = false;
  private int          defaultSubscriptionId = -1;

  Recipients() {
    this(new LinkedList<>(), null);
  }

  Recipients(List<Recipient> recipients, @Nullable RecipientsPreferences preferences) {
    this.recipients = recipients;

    if (preferences != null) {
      ringtone              = preferences.getRingtone();
      mutedUntil            = preferences.getMuteUntil();
      vibrate               = preferences.getVibrateState();
      blocked               = preferences.isBlocked();
      defaultSubscriptionId = preferences.getDefaultSubscriptionId().or(-1);
    }
  }

  Recipients(@NonNull  List<Recipient> recipients,
             @Nullable Recipients stale,
             @NonNull  ListenableFutureTask<RecipientsPreferences> preferences)
  {
    this.recipients = recipients;

    if (stale != null) {
      ringtone              = stale.ringtone;
      mutedUntil            = stale.mutedUntil;
      vibrate               = stale.vibrate;
      blocked               = stale.blocked;
      defaultSubscriptionId = stale.defaultSubscriptionId;
    }

    preferences.addListener(new FutureTaskListener<>() {
      @Override
      public void onSuccess(RecipientsPreferences result) {
        if (result != null) {

          Set<RecipientsModifiedListener> localListeners;

          synchronized (Recipients.this) {
            ringtone = result.getRingtone();
            mutedUntil = result.getMuteUntil();
            vibrate = result.getVibrateState();
            blocked = result.isBlocked();
            defaultSubscriptionId = result.getDefaultSubscriptionId().or(-1);

            localListeners = new HashSet<>(listeners);
          }

          for (RecipientsModifiedListener listener : localListeners) {
            listener.onModified(Recipients.this);
          }
        }
      }

      @Override
      public void onFailure(Throwable error) {
        Log.w(TAG, error);
      }
    });
  }

  public synchronized @Nullable Uri getRingtone() {
    return ringtone;
  }

  public void setRingtone(Uri ringtone) {
    synchronized (this) {
      this.ringtone = ringtone;
    }

    notifyListeners();
  }

  public synchronized boolean isMuted() {
    return System.currentTimeMillis() <= mutedUntil;
  }

  public void setMuted(long mutedUntil) {
    synchronized (this) {
      this.mutedUntil = mutedUntil;
    }

    notifyListeners();
  }

  public synchronized boolean isBlocked() {
    return blocked;
  }

  public void setBlocked(boolean blocked) {
    synchronized (this) {
      this.blocked = blocked;
    }

    notifyListeners();
  }

  public synchronized VibrateState getVibrate() {
    return vibrate;
  }

  public void setVibrate(VibrateState vibrate) {
    synchronized (this) {
      this.vibrate = vibrate;
    }

    notifyListeners();
  }

  public @NonNull ContactPhoto getContactPhoto() {
    if (recipients.size() == 1) return recipients.get(0).getContactPhoto();
    else                        return ContactPhotoFactory.getDefaultGroupPhoto();
  }

  public synchronized @NonNull MaterialColor getColor() {
    if      (!isSingleRecipient() || isGroupRecipient()) return MaterialColor.GROUP;
    else if (isEmpty())                                  return ContactColors.UNKNOWN_COLOR;
    else                                                 return recipients.get(0).getColor();
  }

  public synchronized void setColor(@NonNull MaterialColor color) {
    if      (!isSingleRecipient() || isGroupRecipient()) throw new AssertionError("Groups don't have colors!");
    else if (!isEmpty())                                 recipients.get(0).setColor(color);
  }

  public synchronized void addListener(RecipientsModifiedListener listener) {
    if (listeners.isEmpty()) {
      for (Recipient recipient : recipients) {
        recipient.addListener(this);
      }
    }

    listeners.add(listener);
  }

  public synchronized void removeListener(RecipientsModifiedListener listener) {
    listeners.remove(listener);

    if (listeners.isEmpty()) {
      for (Recipient recipient : recipients) {
        recipient.removeListener(this);
      }
    }
  }

  public boolean isEmailRecipient() {
    for (Recipient recipient : recipients) {
      if (NumberUtil.isValidEmail(recipient.getNumber()))
        return true;
    }

    return false;
  }

  public boolean isGroupRecipient() {
    return false;
  }

  public boolean isEmpty() {
    return this.recipients.isEmpty();
  }

  public boolean isSingleRecipient() {
    return this.recipients.size() == 1;
  }

  public @NonNull Recipient getPrimaryRecipient() {
    if (!isEmpty()) {
      return recipients.get(0);
    } else {
      return Recipient.getUnknownRecipient();
    }
  }

  public List<Recipient> getRecipientsList() {
    return this.recipients;
  }

  public long[] getIds() {
    long[] ids = new long[recipients.size()];
    for (int i=0; i<recipients.size(); i++) {
      ids[i] = recipients.get(i).getRecipientId();
    }
    return ids;
  }

  public String getSortedIdsString() {
    Set<Long> recipientSet  = new HashSet<>();

    for (Recipient recipient : this.recipients) {
      recipientSet.add(recipient.getRecipientId());
    }

    long[] recipientArray = new long[recipientSet.size()];
    int i                 = 0;

    for (Long recipientId : recipientSet) {
      recipientArray[i++] = recipientId;
    }

    Arrays.sort(recipientArray);

    return Util.join(recipientArray, " ");
  }

  public @NonNull String[] toNumberStringArray(boolean scrub) {
    String[] recipientsArray = new String[recipients.size()];
    int i = 0;

    for (Recipient recipient : recipients) {
      String number = recipient.getNumber();

      if (scrub && !Patterns.EMAIL_ADDRESS.matcher(number).matches()) {
        number = number.replaceAll("[^0-9+]", "");
      }

      recipientsArray[i++] = number;
    }

    return recipientsArray;
  }

  public @NonNull List<String> toNumberStringList(boolean scrub) {
    List<String> results = new LinkedList<>();
    Collections.addAll(results, toNumberStringArray(scrub));

    return results;
  }

  public String toShortString() {
    StringBuilder fromString = new StringBuilder();

    for (int i=0;i<recipients.size();i++) {
      fromString.append(recipients.get(i).toShortString());

      if (i != recipients.size() -1 )
        fromString.append(", ");
    }

    return fromString.toString();
  }

  @Override
  public @NonNull Iterator<Recipient> iterator() {
    return recipients.iterator();
  }

  @Override
  public void onModified(Recipient recipient) {
    notifyListeners();
  }

  private void notifyListeners() {
    Set<RecipientsModifiedListener> localListeners;

    synchronized (this) {
      localListeners = new HashSet<>(listeners);
    }

    for (RecipientsModifiedListener listener : localListeners) {
      listener.onModified(this);
    }
  }

  boolean isStale() {
    return stale;
  }

  void setStale() {
    this.stale = true;
  }

  boolean isResolving() {
    for (Recipient recipient : recipients) {
      if (recipient.isResolving()) return true;
    }

    return false;
  }

  public interface RecipientsModifiedListener {
    void onModified(Recipients recipient);
  }

}
