/*
 * Copyright (C) 2011 Whisper Systems
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
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jimvixx.smsecure.recipients;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.contacts.avatars.ContactColors;
import org.jimvixx.smsecure.contacts.avatars.ContactPhoto;
import org.jimvixx.smsecure.contacts.avatars.ContactPhotoFactory;
import org.jimvixx.smsecure.database.CanonicalAddressDatabase;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.LRUCache;
import org.jimvixx.smsecure.util.ListenableFutureTask;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.VisibleForTesting;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

class RecipientProvider {

  private static final String TAG = RecipientProvider.class.getSimpleName();

  private static final RecipientCache recipientCache = new RecipientCache();
  private static final RecipientsCache recipientsCache = new RecipientsCache();
  private static final ExecutorService asyncRecipientResolver = Util.newSingleThreadedLifoExecutor();

  private static final String[] CALLER_ID_PROJECTION = new String[]{
          PhoneLookup.DISPLAY_NAME,
          PhoneLookup.LOOKUP_KEY,
          PhoneLookup._ID,
          PhoneLookup.NUMBER
  };

  private static final Map<String, RecipientDetails> STATIC_DETAILS = new HashMap<>() {{
    put("262966", new RecipientDetails("Amazon", "262966", null,
            ContactPhotoFactory.getDefaultGroupPhoto(),
            ContactColors.UNKNOWN_COLOR));
  }};

  Recipient getRecipient(Context context, long recipientId, boolean asynchronous) {
    Recipient cachedRecipient = recipientCache.get(recipientId);
    if (cachedRecipient != null && !cachedRecipient.isStale() && (asynchronous || !cachedRecipient.isResolving())) {
      return cachedRecipient;
    }

    String number = CanonicalAddressDatabase.getInstance(context).getAddressFromId(recipientId);

    if (asynchronous) {
      cachedRecipient = new Recipient(recipientId, number, cachedRecipient, getRecipientDetailsAsync(context, recipientId, number));
    } else {
      cachedRecipient = new Recipient(recipientId, getIndividualRecipientDetails(context, recipientId, number));
    }

    recipientCache.set(recipientId, cachedRecipient);
    return cachedRecipient;
  }

  Recipients getRecipients(Context context, long[] recipientIds, boolean asynchronous) {
    if (recipientIds == null || recipientIds.length == 0) {
      Log.w(TAG, "getRecipients(): recipientIds is null or empty");
      return asynchronous
              ? new Recipients(new LinkedList<>(), null, getRecipientsPreferencesAsync(context, new long[0]))
              : new Recipients(new LinkedList<>(), getRecipientsPreferencesSync(context, new long[0]));
    }

    Recipients cachedRecipients = recipientsCache.get(new RecipientIds(recipientIds));
    if (cachedRecipients != null && !cachedRecipients.isStale() &&
            (asynchronous || !cachedRecipients.isResolving())) {
      return cachedRecipients;
    }

    List<Recipient> recipientList = new LinkedList<>();

    for (long recipientId : recipientIds) {
      recipientList.add(getRecipient(context, recipientId, asynchronous));
    }

    if (asynchronous) {
      cachedRecipients = new Recipients(
              recipientList,
              cachedRecipients,
              getRecipientsPreferencesAsync(context, recipientIds)
      );
    } else {
      cachedRecipients = new Recipients(
              recipientList,
              getRecipientsPreferencesSync(context, recipientIds)
      );
    }

    recipientsCache.set(new RecipientIds(recipientIds), cachedRecipients);
    return cachedRecipients;
  }

  void clearCache() {
    recipientCache.reset();
    recipientsCache.reset();
  }

  private @NonNull ListenableFutureTask<RecipientDetails> getRecipientDetailsAsync(final Context context,
                                                                                   final long recipientId,
                                                                                   final @NonNull String number) {
    Callable<RecipientDetails> task = () -> getIndividualRecipientDetails(context, recipientId, number);

    ListenableFutureTask<RecipientDetails> future = new ListenableFutureTask<>(task);
    asyncRecipientResolver.submit(future);
    return future;
  }

  private @NonNull RecipientDetails getIndividualRecipientDetails(Context context, long recipientId, @NonNull String number) {
    Optional<RecipientsPreferences> preferences = DatabaseFactory.getRecipientPreferenceDatabase(context).getRecipientsPreferences(new long[]{recipientId});
    MaterialColor color = preferences.isPresent() ? preferences.get().getColor() : null;

    return resolveIndividualRecipientDetails(context, number, color);
  }

  @VisibleForTesting
  @NonNull RecipientDetails resolveIndividualRecipientDetails(Context context,
                                                               @NonNull String number,
                                                               @Nullable MaterialColor color) {

    if (!Util.missingContactsPermissions(context)) {
      Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));

      try (Cursor cursor = context.getContentResolver().query(uri, CALLER_ID_PROJECTION, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) {
          final String resultNumber = cursor.getString(3);
          if (resultNumber != null) {
            Uri contactUri = Contacts.getLookupUri(cursor.getLong(2), cursor.getString(1));
            String name = resultNumber.equals(cursor.getString(0)) ? null : cursor.getString(0);
            ContactPhoto contactPhoto = ContactPhotoFactory.getContactPhoto(context,
                    Uri.withAppendedPath(Contacts.CONTENT_URI, cursor.getLong(2) + ""),
                    name);

            return new RecipientDetails(cursor.getString(0), resultNumber, contactUri, contactPhoto, color);
          } else {
            Log.w(TAG, "resultNumber is null");
          }
        }
      } catch (SecurityException se) {
        Log.w(TAG, se);
      }
    }

    if (STATIC_DETAILS.containsKey(number))
      return Objects.requireNonNull(STATIC_DETAILS.get(number));
    else
      return new RecipientDetails(null, number, null, ContactPhotoFactory.getDefaultContactPhoto(null), color);
  }

  private @Nullable RecipientsPreferences getRecipientsPreferencesSync(Context context, long[] recipientIds) {
    return DatabaseFactory.getRecipientPreferenceDatabase(context)
            .getRecipientsPreferences(recipientIds)
            .orNull();
  }

  private ListenableFutureTask<RecipientsPreferences> getRecipientsPreferencesAsync(final Context context, final long[] recipientIds) {
    ListenableFutureTask<RecipientsPreferences> task = new ListenableFutureTask<>(() -> getRecipientsPreferencesSync(context, recipientIds));

    asyncRecipientResolver.execute(task);

    return task;
  }

  public static class RecipientDetails {
    @Nullable
    public final String name;
    @NonNull
    public final String number;
    @NonNull
    public final ContactPhoto avatar;
    @Nullable
    public final Uri contactUri;
    @Nullable
    public final MaterialColor color;

    public RecipientDetails(@Nullable String name, @NonNull String number,
                            @Nullable Uri contactUri, @NonNull ContactPhoto avatar,
                            @Nullable MaterialColor color) {
      this.name = name;
      this.number = number;
      this.avatar = avatar;
      this.contactUri = contactUri;
      this.color = color;
    }
  }

  private static class RecipientIds {
    private final long[] ids;

    private RecipientIds(long[] ids) {
      this.ids = ids;
    }

    public boolean equals(Object other) {
      if (!(other instanceof RecipientIds)) return false;
      return Arrays.equals(this.ids, ((RecipientIds) other).ids);
    }

    public int hashCode() {
      return Arrays.hashCode(ids);
    }
  }

  private static class RecipientCache {

    private final Map<Long, Recipient> cache = new LRUCache<>(1000);

    public synchronized Recipient get(long recipientId) {
      return cache.get(recipientId);
    }

    public synchronized void set(long recipientId, Recipient recipient) {
      cache.put(recipientId, recipient);
    }

    public synchronized void reset() {
      for (Recipient recipient : cache.values()) {
        recipient.setStale();
      }
    }

  }

  private static class RecipientsCache {

    private final Map<RecipientIds, Recipients> cache = new LRUCache<>(1000);

    public synchronized Recipients get(RecipientIds ids) {
      return cache.get(ids);
    }

    public synchronized void set(RecipientIds ids, Recipients recipients) {
      cache.put(ids, recipients);
    }

    public synchronized void reset() {
      for (Recipients recipients : cache.values()) {
        recipients.setStale();
      }
    }

  }


}
