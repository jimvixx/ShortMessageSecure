/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2025 Jimvixx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

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

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.contacts.avatars.ContactColors;
import org.jimvixx.smsecure.contacts.avatars.ContactPhoto;
import org.jimvixx.smsecure.contacts.avatars.ContactPhotoFactory;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.RecipientProvider.RecipientDetails;
import org.jimvixx.smsecure.util.FutureTaskListener;
import org.jimvixx.smsecure.util.ListenableFutureTask;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

public class Recipient {

  private final static String TAG = Recipient.class.getSimpleName();

  private final Set<RecipientModifiedListener> listeners = Collections.newSetFromMap(new WeakHashMap<>());

  private final long recipientId;

  private @NonNull String number;
  private String name;
  private boolean stale;
  private boolean resolving;

  private ContactPhoto contactPhoto;
  private Uri contactUri;

  @Nullable
  private MaterialColor color;

  Recipient(long recipientId,
            @NonNull String number,
            @Nullable Recipient stale,
            @NonNull ListenableFutureTask<RecipientDetails> future) {
    this.recipientId = recipientId;
    this.number = number;
    this.contactPhoto = ContactPhotoFactory.getLoadingPhoto();
    this.color = null;
    this.resolving = true;

    if (stale != null) {
      this.name = stale.name;
      this.contactUri = stale.contactUri;
      this.contactPhoto = stale.contactPhoto;
      this.color = stale.color;
    }

    future.addListener(new FutureTaskListener<>() {
      @Override
      public void onSuccess(RecipientDetails result) {
        if (result != null) {
          synchronized (Recipient.this) {
            Recipient.this.name = result.name;
            Recipient.this.number = result.number;
            Recipient.this.contactUri = result.contactUri;
            Recipient.this.contactPhoto = result.avatar;
            Recipient.this.color = result.color;
            Recipient.this.resolving = false;
          }

          notifyListeners();
        }
      }

      @Override
      public void onFailure(Throwable error) {
        Log.w(TAG, error);
      }
    });
  }

  Recipient(long recipientId, RecipientDetails details) {
    this.recipientId = recipientId;
    this.number = java.util.Objects.requireNonNull(details.number, "RecipientDetails.number == null");
    this.contactUri = details.contactUri;
    this.name = details.name;
    this.contactPhoto = details.avatar;
    this.color = details.color;
    this.resolving = false;
  }

  public static Recipient getUnknownRecipient() {
    return new Recipient(-1, new RecipientDetails("Unknown", "Unknown", null,
            ContactPhotoFactory.getDefaultContactPhoto(null), null));
  }

  public synchronized @Nullable Uri getContactUri() {
    return this.contactUri;
  }

  public synchronized @Nullable String getName() {
    return this.name;
  }

  public synchronized @NonNull MaterialColor getColor() {
    if (color != null) return color;
    else if (name != null) return ContactColors.generateFor(name);
    else return ContactColors.UNKNOWN_COLOR;
  }

  public void setColor(@NonNull MaterialColor color) {
    synchronized (this) {
      this.color = color;
    }

    notifyListeners();
  }

  public synchronized @NonNull String getNumber() {
    return number;
  }

  public long getRecipientId() {
    return recipientId;
  }

  public boolean isGroupRecipient() {
    return false;
  }

  public synchronized void addListener(RecipientModifiedListener listener) {
    listeners.add(listener);
  }

  public synchronized void removeListener(RecipientModifiedListener listener) {
    listeners.remove(listener);
  }

  public synchronized String toShortString() {
    return (name == null ? number : name);
  }

  public synchronized @NonNull ContactPhoto getContactPhoto() {
    return contactPhoto;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Recipient that)) return false;

    return this.recipientId == that.recipientId;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(recipientId);
  }

  private void notifyListeners() {
    Set<RecipientModifiedListener> localListeners;

    synchronized (this) {
      localListeners = new HashSet<>(listeners);
    }

    for (RecipientModifiedListener listener : localListeners)
      listener.onModified(this);
  }

  boolean isStale() {
    return stale;
  }

  void setStale() {
    this.stale = true;
  }

  synchronized boolean isResolving() {
    return resolving;
  }

  public interface RecipientModifiedListener {
    void onModified(Recipient recipient);
  }

}
