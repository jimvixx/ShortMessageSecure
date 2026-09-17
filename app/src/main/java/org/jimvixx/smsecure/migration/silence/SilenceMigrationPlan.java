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

/** Review-only draft. Completing mappings does not authorize importing any data. */
public final class SilenceMigrationPlan {
  private final SilencePreferencePlan preferences;
  private final SilenceSubscriptionPlan subscriptions;
  private final boolean cryptoInventoryChecked;
  SilenceMigrationPlan(SilencePreferencePlan preferences, SilenceSubscriptionPlan subscriptions, boolean cryptoInventoryChecked) {
    this.preferences = preferences; this.subscriptions = subscriptions; this.cryptoInventoryChecked = cryptoInventoryChecked;
  }
  public SilencePreferencePlan getPreferences() { return preferences; }
  public SilenceSubscriptionPlan getSubscriptions() { return subscriptions; }
  public boolean isCryptoInventoryChecked() { return cryptoInventoryChecked; }
}
