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

import android.content.Context;
import android.content.Intent;
import android.view.View.OnClickListener;

import org.jimvixx.smsecure.ConversationListActivity;
import org.jimvixx.smsecure.DatabaseMigrationActivity;
import org.jimvixx.smsecure.PassphraseActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.service.ApplicationMigrationService;
import org.jimvixx.smsecure.crypto.MasterSecret;

public class SystemSmsImportReminder extends Reminder {

  public SystemSmsImportReminder(final Context context, final MasterSecret masterSecret) {
    super(context.getString(R.string.reminder_header_sms_import_title),
            context.getString(R.string.reminder_header_sms_import_text),
            context.getString(R.string.Import));

    final OnClickListener okListener = v -> {
      Intent serviceIntent = new Intent(context, ApplicationMigrationService.class);
      serviceIntent.setAction(ApplicationMigrationService.MIGRATE_DATABASE);
      serviceIntent.putExtra("master_secret", masterSecret);
      context.startService(serviceIntent);

      Intent nextIntent = new Intent(context, ConversationListActivity.class);

      Intent activityIntent = new Intent(context, DatabaseMigrationActivity.class);
      activityIntent.putExtra("master_secret", masterSecret);
      activityIntent.putExtra(PassphraseActivity.EXTRA_NEXT_INTENT, nextIntent);
      context.startActivity(activityIntent);
    };

    final OnClickListener cancelListener = v -> ApplicationMigrationService.setDatabaseImported(context);

    setOkListener(okListener);
    setDismissListener(cancelListener);
  }

  public static boolean isEligible(Context context) {
    return ApplicationMigrationService.isDatabaseNotImported(context);
  }
}
