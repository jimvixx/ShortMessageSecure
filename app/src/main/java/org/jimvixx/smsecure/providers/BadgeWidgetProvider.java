/*
 * Copyright (C) 2014 Open Whisper Systems
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

package org.jimvixx.smsecure.providers;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.ConversationListActivity;
import org.jimvixx.smsecure.R;

public class BadgeWidgetProvider extends AppWidgetProvider {

  public static final int MAX_COUNT = 99;

  public static final String ACTION_UPDATE_BADGE =
          "org.jimvixx.smsecure.providers.BadgeWidgetProvider.ACTION_UPDATE_BADGE";

  public static final String EXTRA_UNREAD_COUNT = "extra_unread_count";

  public BadgeWidgetProvider() {
    // Required empty constructor for AppWidgetProvider.
  }

  public static void updateBadge(@NonNull Context context, int unreadCount) {
    Context appContext = context.getApplicationContext();

    AppWidgetManager manager = AppWidgetManager.getInstance(appContext);
    ComponentName widget = new ComponentName(appContext, BadgeWidgetProvider.class);

    int[] ids = manager.getAppWidgetIds(widget);
    if (ids == null || ids.length == 0) return;

    RemoteViews views = buildRemoteViews(appContext, unreadCount);
    manager.updateAppWidget(ids, views);
  }

  private static RemoteViews buildRemoteViews(@NonNull Context context, int unreadCount) {
    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.badge_widget);

    // Render count
    if (unreadCount <= 0) {
      views.setTextViewText(R.id.widget_number, "");
      views.setViewVisibility(R.id.widget_number, android.view.View.GONE);
    } else {
      views.setViewVisibility(R.id.widget_number, android.view.View.VISIBLE);
      String displayCount = (unreadCount <= MAX_COUNT)
              ? String.valueOf(unreadCount)
              : (MAX_COUNT + "+");
      views.setTextViewText(R.id.widget_number, displayCount);
    }

    // Click opens the conversation list
    PendingIntent pi = getLaunchPendingIntent(context);
    // Keep BOTH ids if you have both in layout; safe if one doesn't exist.
    views.setOnClickPendingIntent(R.id.widget_icon, pi);
    views.setOnClickPendingIntent(R.id.widget_frame, pi);

    return views;
  }

  private static PendingIntent getLaunchPendingIntent(@NonNull Context context) {
    Intent intent = new Intent(context, ConversationListActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getActivity(context, 0, intent, flags);
  }

  @Override
  public void onUpdate(@NonNull Context context,
                       @NonNull AppWidgetManager appWidgetManager,
                       @NonNull int[] appWidgetIds) {
    for (int widgetId : appWidgetIds) {
      RemoteViews views = buildRemoteViews(context, 0);
      appWidgetManager.updateAppWidget(widgetId, views);
    }
  }

  @Override
  public void onReceive(@NonNull Context context, @NonNull Intent intent) {
    super.onReceive(context, intent);

    if (ACTION_UPDATE_BADGE.equals(intent.getAction())) {
      int unread = intent.getIntExtra(EXTRA_UNREAD_COUNT, 0);
      updateBadge(context, unread);
    }
  }
}
