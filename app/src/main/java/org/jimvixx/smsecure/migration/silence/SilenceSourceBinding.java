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

/** Decodes source identifiers only; never maps them to an installed SIM or live recipient. */
final class SilenceSourceBinding {
  final long recipientId;
  final int subscriptionId;
  private SilenceSourceBinding(long recipientId, int subscriptionId) {
    this.recipientId = recipientId;
    this.subscriptionId = subscriptionId;
  }
  static SilenceSourceBinding session(String name) throws IOException {
    String[] parts = name.split("\\.", -1);
    if (parts.length > 2 || !parts[0].matches("[1-9][0-9]{0,18}")) throw new IOException("Invalid source recipient");
    try {
      return new SilenceSourceBinding(Long.parseLong(parts[0]), parts.length == 1 ? -1 : subscription(parts[1]));
    } catch (NumberFormatException e) { throw new IOException("Invalid source recipient"); }
  }
  static int preKeySubscription(String name, int recordId) throws IOException {
    if (recordId < 0) throw new IOException("Unsupported prekey identifier");
    String id = Integer.toString(recordId);
    if (!name.startsWith(id)) throw new IOException("Prekey filename disagrees with record");
    String suffix = name.substring(id.length());
    return suffix.isEmpty() ? -1 : subscription(suffix);
  }
  static int subscription(String value) throws IOException {
    if (!value.matches("0|[1-9][0-9]{0,9}")) throw new IOException("Invalid source subscription");
    try { return Integer.parseInt(value); }
    catch (NumberFormatException e) { throw new IOException("Invalid source subscription"); }
  }
}
