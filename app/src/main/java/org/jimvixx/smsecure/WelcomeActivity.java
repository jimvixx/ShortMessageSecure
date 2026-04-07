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

package org.jimvixx.smsecure;

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.jimvixx.smsecure.notifications.NotificationChannels;
import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.ServiceUtil;
import org.jimvixx.smsecure.util.Util;

public class WelcomeActivity extends BaseActionBarActivity {

  public static final String EXTRA_NEXT_SCREEN = "next_screen";
  public static final String NEXT_SCREEN_CONVERSATION_LIST = "conversation_list";

  private static final int NOTIFICATION_ID = 1339;
  private Mode mode;

  private static void displayPermissionsNotification(Context context) {
    Intent targetIntent =
            context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());

    Notification notification =
            new NotificationCompat.Builder(context, NotificationChannels.OTHER)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setSmallIcon(R.drawable.ic_smsecure)
                    .setColor(resolveThemeColor(context, R.attr.appColorIconPrimary))
                    .setContentTitle(context.getString(R.string.WelcomeActivity_action_required))
                    .setContentText(context.getString(R.string.WelcomeActivity_you_need_to_grant_some_permissions_to_smsecure))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                            context.getString(
                                    R.string.WelcomeActivity_you_need_to_grant_some_permissions_to_smsecure_in_order_to_continue_to_use_it)))
                    .setAutoCancel(false)
                    .setContentIntent(PendingIntent.getActivity(
                            context,
                            0,
                            targetIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build();

    ServiceUtil.getNotificationManager(context).notify(NOTIFICATION_ID, notification);
  }

  public static void checkForPermissions(Context context, Intent intent) {
    if (intent == null) return;

    if (Util.missingMandatoryPermissions(context) && !SMSecurePreferences.isFirstRun(context)) {
      displayPermissionsNotification(context);
    }
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.welcome_activity);

    mode = SMSecurePreferences.isFirstRun(this) ? Mode.FIRST_RUN : Mode.MISSING_PERMS;
    bindUiForMode(mode);

    findViewById(R.id.welcome_continue_button).setOnClickListener(v -> {
      if (mode == Mode.FIRST_RUN) onContinueClicked();
      else onContinueMissingPermsClicked();
    });
  }

  private void bindUiForMode(@NonNull Mode mode) {
    TextView title = findViewById(R.id.welcome_title);
    TextView desc = findViewById(R.id.welcome_description);
    ImageView img = findViewById(R.id.welcome_image);
    Button button = findViewById(R.id.welcome_continue_button);

    if (mode == Mode.FIRST_RUN) {
      title.setText(R.string.WelcomeActivity_improve_your_privacy_talk_to_everyone);
      desc.setText(R.string.WelcomeActivity_encrypt_your_messages);
      img.setImageResource(R.drawable.ic_smsecure);
      button.setText(R.string.Continue);
    } else {
      title.setText(R.string.WelcomeActivity_action_required);
      desc.setText(R.string.WelcomeActivity_smsecure_needs_the_phone_permission_in_order_to_manage_encryption_keys_and_bind_them_to_your_sim_cards_and_sms_permission_in_order_to_receive_sms_messages);
      img.setImageResource(R.drawable.ic_message_alert);
      int tint = resolveThemeColor(this, R.attr.appColorIconPrimary);
      img.setColorFilter(tint);
      button.setText(R.string.Continue);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void onContinueClicked() {
    Permissions.with(this)
            .request(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS,

                    Manifest.permission.READ_PHONE_STATE,

                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.RECEIVE_MMS,

                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS
            )
            .ifNecessary()
            .withRationaleDialog(
                    getString(R.string.WelcomeActivity_smsecure_needs_access_to_your_contacts_phone_status_and_sms)
            )
            .onAllGranted(() -> {
              SMSecurePreferences.setFirstRun(this);
              SMSecurePreferences.setPermissionsAsked(this);
              goToNextScreen();
            })
            .onAnyDenied(() -> {
              SMSecurePreferences.setPermissionsAsked(this);

              if (!Util.missingMandatoryPermissions(this)) {
                SMSecurePreferences.setFirstRun(this);
                goToNextScreen();
              } else {
                mode = Mode.MISSING_PERMS;
                bindUiForMode(mode);
              }
            })
            .execute();
  }

  private void onContinueMissingPermsClicked() {
    Permissions.with(this)
            .request(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.RECEIVE_MMS
            )
            .ifNecessary()
            .withPermanentDenialDialog(
                    getString(R.string.WelcomeActivity_smsecure_requires_the_phone_and_sms_permissions_in_order_to_work_but_it_has_been_permanently_denied)
            )
            .onAllGranted(this::goToNextScreen)
            .onAnyDenied(() -> {
              // Still missing -> stay.
              mode = Mode.MISSING_PERMS;
              bindUiForMode(mode);
            })
            .execute();
  }

  private void goToNextScreen() {
    if (Util.missingMandatoryPermissions(this)) {
      mode = Mode.MISSING_PERMS;
      bindUiForMode(mode);
      return;
    }

    Class<?> target = ConversationListActivity.class;

    startActivity(new Intent(this, target));
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    finish();
  }

  private void setStatusBarColor(int color) {
    getWindow().setStatusBarColor(color);
  }

  private enum Mode {
    FIRST_RUN,
    MISSING_PERMS
  }
}
