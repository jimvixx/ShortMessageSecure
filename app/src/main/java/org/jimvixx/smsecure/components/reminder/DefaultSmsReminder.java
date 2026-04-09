/*
 * Copyright (C) 2015 Open Whisper Systems
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

package org.jimvixx.smsecure.components.reminder;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;

public class DefaultSmsReminder extends Reminder {

  public DefaultSmsReminder(Context context, @Nullable Launcher launcher) {
    super(context.getString(R.string.reminder_header_sms_default_title),
            context.getString(R.string.reminder_header_sms_default_text_mandatory),
            context.getString(R.string.reminder_header_sms_default_button));

    setOkListener(v -> {
      SMSecurePreferences.setPromptedDefaultSmsProvider(context, true);

      Intent intent = buildRequestDefaultSmsIntent(context);
      if (intent == null) {
        // Already default or not supported.
        return;
      }

      if (launcher != null) {
        launcher.launch(intent);
      } else {
        // Fallback: no launcher provided, just fire-and-forget.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
      }
    });

    setDismissListener(v ->
            SMSecurePreferences.setPromptedDefaultSmsProvider(context, true)
    );
  }

  @Nullable
  private static Intent buildRequestDefaultSmsIntent(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 29+
      RoleManager rm = context.getSystemService(RoleManager.class);
      if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
        return rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
      }
      return null;
    }

    Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
    intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.getPackageName());
    return intent;

  }

  public static boolean isEligible(Context context) {
    final boolean isDefault = Util.isDefaultSmsProvider(context);
    if (isDefault) {
      SMSecurePreferences.setPromptedDefaultSmsProvider(context, false);
    }
    return !isDefault && !SMSecurePreferences.hasPromptedDefaultSmsProvider(context);
  }

  public interface Launcher {
    void launch(Intent intent);
  }
}
