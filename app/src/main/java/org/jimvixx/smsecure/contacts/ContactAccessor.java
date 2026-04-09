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

package org.jimvixx.smsecure.contacts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

/**
 * Encapsulates Contact-related logic.
 * <p>
 * Historically it abstracted API differences (1.x vs 2.x). Today it is mainly
 * a centralized helper for contact lookups.
 */
public class ContactAccessor {

  private static final ContactAccessor instance = new ContactAccessor();

  public static synchronized ContactAccessor getInstance() {
    return instance;
  }

  @Nullable
  public String getNameFromContact(@NonNull Context context, @NonNull Uri uri) {
    try (Cursor cursor = context.getContentResolver()
            .query(uri, new String[]{Contacts.DISPLAY_NAME}, null, null, null)) {

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getString(0);
      }
    }

    return null;
  }

  @NonNull
  public ContactData getContactData(@NonNull Context context, @NonNull Uri uri) {
    final String displayName = getNameFromContact(context, uri);
    final long id = parseContactIdFromUri(uri);
    return getContactData(context, displayName, id);
  }

  private long parseContactIdFromUri(@NonNull Uri uri) {
    final String last = uri.getLastPathSegment();
    if (last == null) return -1;

    try {
      return Long.parseLong(last);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  @NonNull
  private ContactData getContactData(@NonNull Context context,
                                     @Nullable String displayName,
                                     long id) {
    ContactData contactData = new ContactData(id, displayName);

    try (Cursor numberCursor = context.getContentResolver().query(
            Phone.CONTENT_URI,
            null,
            Phone.CONTACT_ID + " = ?",
            new String[]{String.valueOf(contactData.id)},
            null)) {

      while (numberCursor != null && numberCursor.moveToNext()) {
        int type = numberCursor.getInt(numberCursor.getColumnIndexOrThrow(Phone.TYPE));
        String label = numberCursor.getString(numberCursor.getColumnIndexOrThrow(Phone.LABEL));
        String number = numberCursor.getString(numberCursor.getColumnIndexOrThrow(Phone.NUMBER));
        String typeLabel = Phone.getTypeLabel(context.getResources(), type, label).toString();

        contactData.numbers.add(new NumberData(typeLabel, number));
      }
    }

    return contactData;
  }

  /**
   * Thread search helper: returns unique phone numbers that match the constraint.
   */
  @NonNull
  public List<String> getNumbersForThreadSearchFilter(@NonNull Context context,
                                                      @Nullable String constraint) {
    final String q = (constraint == null) ? "" : constraint.trim();
    final int MAX_NUMBERS = 80;

    if (q.isEmpty()) return new ArrayList<>();

    LinkedHashSet<String> unique = new LinkedHashSet<>();

    try (Cursor cursor = context.getContentResolver().query(
            Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(q)),
            new String[]{Phone.NUMBER},
            null,
            null,
            null)) {

      while (cursor != null && cursor.moveToNext()) {
        String number = cursor.getString(cursor.getColumnIndexOrThrow(Phone.NUMBER));
        if (number == null) continue;

        number = number.trim();
        if (number.isEmpty()) continue;

        // ignore emails / sip etc.
        if (number.contains("@")) continue;

        unique.add(number);

        if (unique.size() >= MAX_NUMBERS) break;
      }
    }

    return new ArrayList<>(unique);
  }

  @NonNull
  public CharSequence phoneTypeToString(@NonNull Context context, int type, @Nullable CharSequence label) {
    return Phone.getTypeLabel(context.getResources(), type, label);
  }

  public Cursor getCursorForRecipientFilter(@Nullable CharSequence constraint,
                                            @NonNull ContentResolver contentResolver) {

    final String[] PROJECTION_PHONE = {
            Phone._ID,          // 0
            Phone.CONTACT_ID,   // 1
            Phone.TYPE,         // 2
            Phone.NUMBER,       // 3
            Phone.LABEL,        // 4
            Phone.DISPLAY_NAME, // 5
    };

    final String sortOrder =
            Contacts.TIMES_CONTACTED + " DESC, " +
                    Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC, " +
                    Contacts.Data.IS_SUPER_PRIMARY + " DESC, " +
                    Phone.TYPE + " ASC";

    final String q = (constraint == null) ? "" : constraint.toString().trim();

    if (TextUtils.isEmpty(q)) {
      return contentResolver.query(Phone.CONTENT_URI,
              PROJECTION_PHONE,
              null,
              null,
              sortOrder);
    }

    // Optional: if user typed letters, convert to digits (T9 keypad), then search by that too.
    // This preserves a nice UX for "1-800-FLOWERS" style input, but doesn't inject synthetic rows.
    final String digits = PhoneNumberUtils.convertKeypadLettersToDigits(q).trim();
    final boolean hasDifferentDigits = !digits.equals(q) && !TextUtils.isEmpty(digits);

    // Build LIKE pattern safely.
    final String likeQ = "%" + escapeLike(q) + "%";
    final String likeD = "%" + escapeLike(digits) + "%";

    final String selection;
    final String[] selectionArgs;

    if (hasDifferentDigits) {
      selection =
              "(" + Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\' OR " + Phone.NUMBER + " LIKE ? ESCAPE '\\')" +
                      " OR (" + Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\' OR " + Phone.NUMBER + " LIKE ? ESCAPE '\\')";
      selectionArgs = new String[]{likeQ, likeQ, likeD, likeD};
    } else {
      selection =
              Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\' OR " +
                      Phone.NUMBER + " LIKE ? ESCAPE '\\'";
      selectionArgs = new String[]{likeQ, likeQ};
    }

    return contentResolver.query(Phone.CONTENT_URI,
            PROJECTION_PHONE,
            selection,
            selectionArgs,
            sortOrder);
  }

  /**
   * Escape characters that are special for SQL LIKE.
   * We use backslash as ESCAPE char.
   */
  @NonNull
  private String escapeLike(@NonNull String input) {
    // Order matters.
    return input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
  }

  public static class NumberData implements Parcelable {

    public static final Parcelable.Creator<NumberData> CREATOR = new Parcelable.Creator<>() {
      @Override
      public NumberData createFromParcel(Parcel in) {
        return new NumberData(in);
      }

      @Override
      public NumberData[] newArray(int size) {
        return new NumberData[size];
      }
    };

    public final String number;
    public final String type;

    public NumberData(@Nullable String type, @Nullable String number) {
      this.type = type;
      this.number = number;
    }

    public NumberData(Parcel in) {
      number = in.readString();
      type = in.readString();
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      dest.writeString(number);
      dest.writeString(type);
    }
  }

  public static class ContactData implements Parcelable {

    public static final Parcelable.Creator<ContactData> CREATOR = new Parcelable.Creator<>() {
      @Override
      public ContactData createFromParcel(Parcel in) {
        return new ContactData(in);
      }

      @Override
      public ContactData[] newArray(int size) {
        return new ContactData[size];
      }
    };

    public final long id;
    public final String name;
    public final List<NumberData> numbers;

    public ContactData(long id, @Nullable String name) {
      this.id = id;
      this.name = name;
      this.numbers = new LinkedList<>();
    }

    public ContactData(Parcel in) {
      id = in.readLong();
      name = in.readString();
      numbers = new LinkedList<>();
      in.readTypedList(numbers, NumberData.CREATOR);
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      dest.writeLong(id);
      dest.writeString(name);
      dest.writeTypedList(numbers);
    }
  }
}