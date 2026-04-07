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

package org.jimvixx.smsecure.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.jimvixx.smsecure.ShareActivity;
import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.BitmapUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RequiresApi(api = Build.VERSION_CODES.N_MR1) // API 25
final class DirectShareShortcutsPublisherApi25 {

  private static final int MAX_TARGETS = 10;
  private static final int ICON_SIZE_PX = 192;
  private static final String SHORTCUT_PREFIX = "share_thread_";
  private static final String SHARE_CATEGORY = "org.jimvixx.smsecure.SHARE_TARGET";

  private DirectShareShortcutsPublisherApi25() {}

  static void refreshAsync(@NonNull Context context) {
    final Context appContext = context.getApplicationContext();

    AppExecutors.background().execute(() -> {
      List<ShortcutInfo> shortcuts = buildShortcuts(appContext);
      AppExecutors.mainHandler().post(() -> apply(appContext, shortcuts));
    });
  }

  private static void apply(@NonNull Context context,
                            @NonNull List<ShortcutInfo> shortcuts) {
    ShortcutManager sm = context.getSystemService(ShortcutManager.class);
    if (sm != null) {
      sm.setDynamicShortcuts(shortcuts);
    }
  }

  @NonNull
  private static List<ShortcutInfo> buildShortcuts(@NonNull Context context) {
    MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
    if (masterSecret == null) return Collections.emptyList();

    ThreadDatabase db = DatabaseFactory.getThreadDatabase(context);
    List<ShortcutInfo> result = new ArrayList<>(MAX_TARGETS);

    try (Cursor c = db.getDirectShareList()) {
      ThreadDatabase.Reader reader =
              db.readerFor(c, new MasterCipher(masterSecret));

      ThreadRecord r;
      while ((r = reader.getNext()) != null && result.size() < MAX_TARGETS) {

        Recipients recipients =
                RecipientFactory.getRecipientsForIds(context,
                        r.getRecipients().getIds(),
                        false);

        Intent intent = new Intent(context, ShareActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.setComponent(new ComponentName(context, ShareActivity.class));
        intent.putExtra(ShareActivity.EXTRA_THREAD_ID, r.getThreadId());
        intent.putExtra(ShareActivity.EXTRA_RECIPIENT_IDS, recipients.getIds());
        intent.putExtra(ShareActivity.EXTRA_DISTRIBUTION_TYPE, r.getDistributionType());

        ShortcutInfo.Builder b =
                new ShortcutInfo.Builder(context, SHORTCUT_PREFIX + r.getThreadId())
                        .setShortLabel(recipients.toShortString())
                        .setLongLabel(recipients.toShortString())
                        .setIntent(intent)
                        .setCategories(Collections.singleton(SHARE_CATEGORY));

        Icon icon = buildIcon(context, recipients);
        if (icon != null) b.setIcon(icon);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          b.setLongLived(true);
        }

        result.add(b.build());
      }
    } catch (RuntimeException ignored) {}

    return result;
  }

  private static @Nullable Icon buildIcon(@NonNull Context c, @NonNull Recipients r) {
    try {
      Drawable d = r.getContactPhoto()
              .asDrawable(c, r.getColor().toConversationColor(c));
      Bitmap b = BitmapUtil.createFromDrawable(d, ICON_SIZE_PX, ICON_SIZE_PX);
      return (b != null) ? Icon.createWithBitmap(b) : null;
    } catch (Throwable t) {
      return null;
    }
  }
}
