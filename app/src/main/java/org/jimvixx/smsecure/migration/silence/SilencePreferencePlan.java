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

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Explicit review candidates only; never a replacement for an Android preferences file. */
public final class SilencePreferencePlan {
  private final Map<String, Boolean> candidates;
  private final int deferredCount;
  private SilencePreferencePlan(Map<String, Boolean> candidates, int deferredCount) {
    this.candidates = Collections.unmodifiableMap(new TreeMap<>(candidates));
    this.deferredCount = deferredCount;
  }
  static SilencePreferencePlan from(Map<String, String> source) throws IOException {
    Map<String, Boolean> candidates = new TreeMap<>();
    for (String key : new String[]{"pref_key_enable_notifications", "pref_key_inthread_notifications",
        "pref_show_sent_time", "pref_hide_unread_message_divider", "pref_system_emoji"}) {
      if (!source.containsKey(key)) continue;
      String value = source.get(key);
      if (!"boolean:true".equals(value) && !"boolean:false".equals(value))
        throw new IOException("Invalid preference candidate type");
      candidates.put(key, "boolean:true".equals(value));
    }
    return new SilencePreferencePlan(candidates, source.size() - candidates.size());
  }
  public Map<String, Boolean> getCandidates() { return candidates; }
  public int getDeferredCount() { return deferredCount; }
}
