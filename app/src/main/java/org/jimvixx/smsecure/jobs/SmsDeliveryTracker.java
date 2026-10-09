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
package org.jimvixx.smsecure.jobs;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;

/** Persistent part results; no recipients, message bodies or raw reports. */
final class SmsDeliveryTracker {
  static final int IGNORE = -1;
  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("sms_delivery_attempts", Context.MODE_PRIVATE);
  }

  static synchronized void start(Context context, long id, String attempt, long sentAt, int parts) {
    if (attempt == null || parts < 1 || parts > 4096) return;
    if (!prefs(context).edit().putString("message_" + id,
            new State(attempt, sentAt, parts).encode()).commit()) {
      org.jimvixx.smsecure.logging.Log.w("SmsDeliveryTracker", "Cannot persist delivery attempt");
    }
  }

  static synchronized int record(Context context, long id, String attempt, long sentAt,
                                 int part, int parts, int status) {
    State state = State.decode(prefs(context).getString("message_" + id, null));
    if (state == null || !state.matches(attempt, sentAt, part, parts)) return IGNORE;
    int aggregate = state.record(part, status);
    if (!prefs(context).edit().putString("message_" + id, state.encode()).commit()) {
      throw new IllegalStateException("Cannot persist delivery result");
    }
    return aggregate;
  }

  // Call only after the terminal database status was persisted. An old callback
  // must never delete the tracking state of a newer send attempt.
  static synchronized void finish(Context context, long id, String attempt, long sentAt) {
    SharedPreferences preferences = prefs(context);
    State state = State.decode(preferences.getString("message_" + id, null));
    if (state == null || !state.attempt.equals(attempt) || state.sentAt != sentAt) return;
    if (!preferences.edit().remove("message_" + id).commit()) {
      throw new IllegalStateException("Cannot remove delivery attempt");
    }
  }

  static final class State {
    final String attempt;
    final long sentAt;
    final int[] results;
    State(String attempt, long sentAt, int parts) {
      this.attempt = attempt;
      this.sentAt = sentAt;
      results = new int[parts];
      Arrays.fill(results, 0x20);
    }
    int record(int part, int status) {
      // Terminal part results cannot be undone by duplicate or out-of-order reports.
      if (results[part] == 0x20) results[part] = status;
      for (int value : results) if (value >= 0x40) return value;
      for (int value : results) if (value != 0) return 0x20;
      return 0;
    }
    String encode() {
      StringBuilder out = new StringBuilder(attempt).append('|').append(sentAt);
      for (int value : results) out.append('|').append(value);
      return out.toString();
    }
    boolean matches(String candidate, long timestamp, int part, int parts) {
      return attempt.equals(candidate) && sentAt == timestamp && parts == results.length
              && part >= 0 && part < parts;
    }
    static State decode(String encoded) {
      if (encoded == null) return null;
      try {
        String[] fields = encoded.split("\\|", -1);
        if (fields.length < 3 || fields.length > 4098 || fields[0].isEmpty()) return null;
        State state = new State(fields[0], Long.parseLong(fields[1]), fields.length - 2);
        for (int i = 2; i < fields.length; i++) {
          int value = Integer.parseInt(fields[i]);
          if (value != 0 && value != 0x20 && (value < 0x40 || value > 0x7f)) return null;
          state.results[i - 2] = value;
        }
        return state;
      } catch (RuntimeException malformed) { return null; }
    }
  }
}
