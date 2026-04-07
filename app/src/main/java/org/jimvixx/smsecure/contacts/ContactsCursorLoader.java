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

package org.jimvixx.smsecure.contacts;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.text.TextUtils;
import org.jimvixx.smsecure.logging.Log;

import androidx.annotation.NonNull;
import androidx.loader.content.CursorLoader;

import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.util.NumberUtil;

import java.util.ArrayList;

/**
 * CursorLoader that merges SMSSecure contacts and system contacts.
 *
 * Important:
 * MergeCursor merges rows by column INDEX, not by column NAME.
 * If underlying cursors have the same columns in different orders, data gets scrambled.
 *
 * We normalize every cursor to a stable schema (same columns in same order) before merging.
 */
public class ContactsCursorLoader extends CursorLoader {

  private static final String TAG = ContactsCursorLoader.class.getSimpleName();

  private final String  filter;
  private final boolean includeSmsContacts;

  // Stable schema used for MergeCursor (order matters!)
  private static final String[] STABLE_COLUMNS = new String[] {
          ContactsDatabase.ID_COLUMN,
          ContactsDatabase.CONTACT_TYPE_COLUMN,
          ContactsDatabase.NAME_COLUMN,
          ContactsDatabase.NUMBER_COLUMN,
          ContactsDatabase.NUMBER_TYPE_COLUMN,
          ContactsDatabase.LABEL_COLUMN
  };

  public ContactsCursorLoader(Context context, boolean includeSmsContacts, String filter) {
    super(context);
    this.filter              = filter;
    this.includeSmsContacts  = includeSmsContacts;
  }

  @Override
  public Cursor loadInBackground() {
    ContactsDatabase contactsDatabase = DatabaseFactory.getContactsDatabase(getContext());
    ArrayList<Cursor> cursorList      = new ArrayList<>(3);

    Cursor smsecure = null;
    Cursor system   = null;
    Cursor typedNew = null;

    try {
      smsecure = contactsDatabase.querySMSecureContacts(filter);
      cursorList.add(normalize(smsecure, "smsecure"));

      if (includeSmsContacts) {
        system = contactsDatabase.querySystemContacts(filter);
        cursorList.add(normalize(system, "system"));
      }

      if (!TextUtils.isEmpty(filter) && NumberUtil.isValidSmsOrEmail(filter)) {
        typedNew = contactsDatabase.getNewNumberCursor(filter);
        cursorList.add(normalize(typedNew, "new-number"));
      }

      return new MergeCursor(cursorList.toArray(new Cursor[0]));
    } catch (Throwable t) {
      Log.w(TAG, "Failed to load contacts.", t);
      // Return empty stable cursor to avoid crashes.
      return new MatrixCursor(STABLE_COLUMNS);
    } finally {
      // We copied data into MatrixCursor, so close originals.
      closeQuietly(smsecure);
      closeQuietly(system);
      closeQuietly(typedNew);
    }
  }

  private static void closeQuietly(Cursor c) {
    try {
      if (c != null) c.close();
    } catch (Throwable ignored) {
    }
  }

  /**
   * Normalize an arbitrary cursor into a MatrixCursor with STABLE_COLUMNS order.
   * This avoids MergeCursor column-index corruption when sources use different projections.
   */
  private Cursor normalize(@NonNull Cursor source, @NonNull String label) {
    MatrixCursor out = new MatrixCursor(STABLE_COLUMNS);

    // Resolve indices by NAME in the source cursor.
    final int idxId         = source.getColumnIndex(ContactsDatabase.ID_COLUMN);
    final int idxType       = source.getColumnIndex(ContactsDatabase.CONTACT_TYPE_COLUMN);
    final int idxName       = source.getColumnIndex(ContactsDatabase.NAME_COLUMN);
    final int idxNumber     = source.getColumnIndex(ContactsDatabase.NUMBER_COLUMN);
    final int idxNumberType = source.getColumnIndex(ContactsDatabase.NUMBER_TYPE_COLUMN);
    final int idxLabel      = source.getColumnIndex(ContactsDatabase.LABEL_COLUMN);

    if (idxId < 0 || idxType < 0) {
      Log.w(TAG, "Cursor '" + label + "' is missing required columns. id=" + idxId + " type=" + idxType);
      return out;
    }

    if (source.moveToFirst()) {
      do {
        Object[] row = new Object[STABLE_COLUMNS.length];

        row[0] = (idxId >= 0) ? source.getLong(idxId) : 0L;
        row[1] = (idxType >= 0) ? source.getInt(idxType) : 0;
        row[2] = (idxName >= 0) ? source.getString(idxName) : null;
        row[3] = (idxNumber >= 0) ? source.getString(idxNumber) : null;
        row[4] = (idxNumberType >= 0) ? source.getInt(idxNumberType) : 0;
        row[5] = (idxLabel >= 0) ? source.getString(idxLabel) : null;

        out.addRow(row);
      } while (source.moveToNext());
    }

    return out;
  }
}
