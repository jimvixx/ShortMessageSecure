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

package org.jimvixx.smsecure.migration.silence;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds a disposable draft from source evidence only; never reads target preferences or SIMs. */
final class SilenceMigrationPlanner {
  SilenceMigrationPlan plan(SilenceBackupStager.Snapshot snapshot, SilenceCryptoVerificationInfo crypto) throws IOException {
    checkCancelled();
    Map<String, String> defaults = SilencePreferencesReader.read(new File(snapshot.root(), SilenceBackupDetector.DEFAULT_PREFS));
    SilencePreferencePlan preferences = SilencePreferencePlan.from(defaults);
    Map<Integer, Set<SilenceSubscriptionPlan.Origin>> slots = new TreeMap<>();
    Map<String, String> secrets = SilencePreferencesReader.read(new File(snapshot.root(), SilenceBackupDetector.SECRET_PREFS));
    for (String name : secrets.keySet()) {
      checkCancelled();
      for (String prefix : new String[]{SilenceIdentityVerifier.PUBLIC, SilenceIdentityVerifier.PRIVATE}) {
        if (!name.startsWith(prefix)) continue;
        String suffix = name.substring(prefix.length());
        if (!suffix.isEmpty() && !suffix.startsWith("_")) throw new IOException("Invalid source identity slot");
        add(slots, suffix.isEmpty() ? -1 : SilenceSourceBinding.subscription(suffix.substring(1)), SilenceSubscriptionPlan.Origin.IDENTITY);
      }
    }
    // Phone numbers and ICC IDs are deliberately neither retained nor used to select a target SIM.
    for (String name : defaults.keySet()) {
      checkCancelled();
      for (String prefix : new String[]{"number_for_app_subscription_id_", "icc_id_for_app_subscription_id_"})
        if (name.startsWith(prefix)) add(slots, SilenceSourceBinding.subscription(name.substring(prefix.length())),
            SilenceSubscriptionPlan.Origin.SOURCE_METADATA);
    }
    File sessions = new File(snapshot.root(), "files/sessions-v2");
    if (sessions.exists()) {
      File[] files = sessions.listFiles();
      if (files == null) throw new IOException("Unreadable source sessions");
      for (File file : files) {
        checkCancelled();
        add(slots, SilenceSourceBinding.session(file.getName()).subscriptionId, SilenceSubscriptionPlan.Origin.SESSION);
      }
    }
    long unknownSms = 0;
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(snapshot.root(), "databases/messages.db").getAbsolutePath(),
        null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, ignored -> { })) {
      try (Cursor cursor = db.rawQuery("SELECT subscription_id, count(*) FROM sms GROUP BY subscription_id", null)) {
        while (cursor.moveToNext()) {
          checkCancelled(); int slot = databaseSlot(cursor);
          if (slot == -1) unknownSms += cursor.getLong(1);
          else add(slots, slot, SilenceSubscriptionPlan.Origin.SMS);
        }
      }
      try (Cursor cursor = db.rawQuery("SELECT DISTINCT default_subscription_id FROM recipient_preferences", null)) {
        while (cursor.moveToNext()) {
          checkCancelled(); int slot = databaseSlot(cursor);
          if (slot != -1) add(slots, slot, SilenceSubscriptionPlan.Origin.RECIPIENT_DEFAULT);
        }
      }
    } catch (android.database.SQLException e) { throw new IOException("Invalid source subscription data"); }
    boolean checked = crypto != null && crypto.getStatus() == SilenceCryptoVerificationInfo.Status.VERIFIED
        && crypto.getFiles() != null && crypto.getFiles().getStatus() == SilenceCryptoFileInfo.Status.READABLE
        && crypto.getIdentities() != null && crypto.getIdentities().getStatus() != SilenceIdentityInfo.Status.REJECTED;
    // Verified prekeys require an exact source identity slot, so their slots are covered by the identity inventory.
    return new SilenceMigrationPlan(preferences, new SilenceSubscriptionPlan(slots, unknownSms), checked);
  }
  private static int databaseSlot(Cursor cursor) throws IOException {
    if (cursor.isNull(0)) return -1;
    long value = cursor.getLong(0);
    if (cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER || value < -1 || value > Integer.MAX_VALUE)
      throw new IOException("Unsupported source subscription identifier");
    return (int) value;
  }
  private static void add(Map<Integer, Set<SilenceSubscriptionPlan.Origin>> slots, int id, SilenceSubscriptionPlan.Origin origin)
      throws IOException {
    if (!slots.containsKey(id)) {
      if (slots.size() >= 1024) throw new IOException("Too many source subscription slots");
      slots.put(id, EnumSet.noneOf(SilenceSubscriptionPlan.Origin.class));
    }
    slots.get(id).add(origin);
  }
  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Migration planning cancelled");
  }
}
