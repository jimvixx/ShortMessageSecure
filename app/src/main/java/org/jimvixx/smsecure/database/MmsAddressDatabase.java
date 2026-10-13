/*
 * Copyright (C) 2026 Jimvixx
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
package org.jimvixx.smsecure.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to MMS PDU addresses. */
public final class MmsAddressDatabase {
  private final SQLiteOpenHelper databaseHelper;
  public MmsAddressDatabase(@NonNull Context context, @NonNull SQLiteOpenHelper databaseHelper) {
    this.databaseHelper = databaseHelper;
  }
  @NonNull public List<AddressRecord> getAddressesForMessage(long mmsId) {
    List<AddressRecord> result = new ArrayList<>();
    try (Cursor c = databaseHelper.getReadableDatabase().query("mms_addresses",
            new String[]{"_id", "mms_id", "type", "address", "address_charset"}, "mms_id = ?",
            new String[]{String.valueOf(mmsId)}, null, null, "_id ASC")) {
      while (c.moveToNext()) result.add(new AddressRecord(c.getLong(0), c.getLong(1), c.getInt(2),
              c.getString(3), c.getInt(4)));
    }
    return result;
  }
  public static final class AddressRecord {
    public final long id, mmsId; public final int type, charset; public final String address;
    private AddressRecord(long id, long mmsId, int type, String address, int charset) {
      this.id=id; this.mmsId=mmsId; this.type=type; this.address=address; this.charset=charset;
    }
  }
}
