/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.util.dualsim;

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.IdentityKeyUtil;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.storage.SMSecureSessionStore;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.NotificationChannels;
import org.jimvixx.smsecure.util.ServiceUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.util.List;

public final class DualSimUtil {

  private static final String TAG = DualSimUtil.class.getSimpleName();

  private static final int NOTIFICATION_ID = 1340;
  private static final int INVALID_SUBSCRIPTION_ID = -1;

  private DualSimUtil() {
  }

  public static void moveIdentityKeysAndSessionsToSubscriptionId(@NonNull Context context,
                                                                 int originalSubscriptionId,
                                                                 int subscriptionId) {
    Context appContext = context.getApplicationContext();

    Log.w(TAG, "moveIdentityKeysAndSessionsToSubscriptionId(" +
            originalSubscriptionId + ", " + subscriptionId + ")");

    moveIdentityKeysToSubscriptionId(appContext, originalSubscriptionId, subscriptionId);
    moveSessionsToSubscriptionId(appContext, originalSubscriptionId, subscriptionId);
  }

  private static void moveIdentityKeysToSubscriptionId(@NonNull Context context,
                                                       int originalSubscriptionId,
                                                       int subscriptionId) {
    if (originalSubscriptionId == subscriptionId) {
      Log.w(TAG, "Skipping identity key move: subscription IDs are identical");
      return;
    }

    String originalIdentityPublicPref = IdentityKeyUtil.getIdentityPublicKeyDjbPref(originalSubscriptionId);
    String targetIdentityPublicPref = IdentityKeyUtil.getIdentityPublicKeyDjbPref(subscriptionId);
    String originalIdentityPrivatePref = IdentityKeyUtil.getIdentityPrivateKeyDjbPref(originalSubscriptionId);
    String targetIdentityPrivatePref = IdentityKeyUtil.getIdentityPrivateKeyDjbPref(subscriptionId);

    Log.w(TAG, "Moving " + originalIdentityPublicPref + " to " + targetIdentityPublicPref);
    Log.w(TAG, "Moving " + originalIdentityPrivatePref + " to " + targetIdentityPrivatePref);

    String identityPublicKey = IdentityKeyUtil.retrieve(context, originalIdentityPublicPref);
    String identityPrivateKey = IdentityKeyUtil.retrieve(context, originalIdentityPrivatePref);

    if (identityPublicKey == null && identityPrivateKey == null) {
      Log.w(TAG, "No identity keys found for original subscription ID " + originalSubscriptionId);
      return;
    }

    if (identityPublicKey != null) {
      IdentityKeyUtil.save(context, targetIdentityPublicPref, identityPublicKey);
    }

    if (identityPrivateKey != null) {
      IdentityKeyUtil.save(context, targetIdentityPrivatePref, identityPrivateKey);
    }

    IdentityKeyUtil.remove(context, originalIdentityPublicPref);
    IdentityKeyUtil.remove(context, originalIdentityPrivatePref);
  }

  private static void moveSessionsToSubscriptionId(@NonNull Context context,
                                                   int originalSubscriptionId,
                                                   int subscriptionId) {
    if (originalSubscriptionId == subscriptionId) {
      Log.w(TAG, "Skipping session move: subscription IDs are identical");
      return;
    }

    File sessionDirectory = SMSecureSessionStore.getSessionDirectory(context);

    if (!sessionDirectory.exists() || !sessionDirectory.isDirectory()) {
      Log.w(TAG, "Session directory does not exist or is not a directory: " +
              sessionDirectory.getAbsolutePath());
      return;
    }

    File[] sessionFiles = sessionDirectory.listFiles();

    if (sessionFiles == null || sessionFiles.length == 0) {
      Log.w(TAG, "No session files found in: " + sessionDirectory.getAbsolutePath());
      return;
    }

    String originalSuffix = buildSessionSuffix(originalSubscriptionId);
    String targetSuffix = buildSessionSuffix(subscriptionId);

    for (File sessionFile : sessionFiles) {
      if (sessionFile == null || !sessionFile.isFile()) {
        continue;
      }

      String sourcePath = sessionFile.getAbsolutePath();
      String targetPath = buildTargetSessionPath(sourcePath, originalSuffix, targetSuffix, originalSubscriptionId);

      if (targetPath == null) {
        continue;
      }

      if (sourcePath.equals(targetPath)) {
        continue;
      }

      File targetFile = new File(targetPath);

      if (targetFile.exists()) {
        Log.w(TAG, "Target session file already exists, skipping move: " + targetPath);
        continue;
      }

      Log.w(TAG, "Moving session " + sourcePath + " to " + targetPath);

      if (sessionFile.renameTo(targetFile)) {
        Log.w(TAG, "Session moved successfully");
      } else {
        Log.w(TAG, "Failed to move session file");
      }
    }
  }

  private static String buildSessionSuffix(int subscriptionId) {
    return subscriptionId != INVALID_SUBSCRIPTION_ID ? "." + subscriptionId : "";
  }

  private static String buildTargetSessionPath(@NonNull String sourcePath,
                                               @NonNull String originalSuffix,
                                               @NonNull String targetSuffix,
                                               int originalSubscriptionId) {
    if (originalSubscriptionId == INVALID_SUBSCRIPTION_ID) {
      if (!sourcePath.endsWith(targetSuffix)) {
        return sourcePath + targetSuffix;
      }

      return null;
    }

    if (!originalSuffix.isEmpty() && sourcePath.endsWith(originalSuffix)) {
      return sourcePath.substring(0, sourcePath.length() - originalSuffix.length()) + targetSuffix;
    }

    return null;
  }

  public static void generateKeysIfDoNotExist(@NonNull Context context,
                                              @NonNull MasterSecret masterSecret,
                                              List<SubscriptionInfoCompat> activeSubscriptions) {
    generateKeysIfDoNotExist(context, masterSecret, activeSubscriptions, true);
  }

  public static void generateKeysIfDoNotExist(@NonNull Context context,
                                              @NonNull MasterSecret masterSecret,
                                              List<SubscriptionInfoCompat> activeSubscriptions,
                                              boolean displayNotification) {
    if (activeSubscriptions == null || activeSubscriptions.isEmpty()) {
      Log.w(TAG, "No active subscriptions available for key generation");
      return;
    }

    Context appContext = context.getApplicationContext();

    for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
      if (subscriptionInfo == null) {
        continue;
      }

      int subscriptionId = subscriptionInfo.getSubscriptionId();

      if (subscriptionId < 0) {
        Log.w(TAG, "Skipping invalid app subscription ID: " + subscriptionId);
        continue;
      }

      if (!IdentityKeyUtil.hasIdentityKey(appContext, subscriptionId)) {
        Log.w(TAG, "Generating identity keys for app subscription ID " + subscriptionId);
        IdentityKeyUtil.generateIdentityKeys(appContext, masterSecret, subscriptionId, displayNotification);
      }
    }
  }

  public static int getSubscriptionIdFromAppSubscriptionId(@NonNull Context context,
                                                           int appSubscriptionId) {
    Optional<SubscriptionInfoCompat> subscriptionInfo =
            SubscriptionManagerCompat.from(context)
                    .getActiveSubscriptionInfo(appSubscriptionId);

    return subscriptionInfo.isPresent()
            ? subscriptionInfo.get().getDeviceSubscriptionId()
            : INVALID_SUBSCRIPTION_ID;
  }

  public static int getSubscriptionIdFromDeviceSubscriptionId(@NonNull Context context,
                                                              int deviceSubscriptionId) {
    Optional<SubscriptionInfoCompat> subscriptionInfo =
            SubscriptionManagerCompat.from(context)
                    .getActiveSubscriptionInfoFromDeviceSubscriptionId(deviceSubscriptionId);

    return subscriptionInfo.isPresent()
            ? subscriptionInfo.get().getSubscriptionId()
            : INVALID_SUBSCRIPTION_ID;
  }

  public static void displayNotification(@NonNull Context context) {
    Context appContext = context.getApplicationContext();

    Intent targetIntent = appContext.getPackageManager()
            .getLaunchIntentForPackage(appContext.getPackageName());

    if (targetIntent == null) {
      Log.w(TAG, "Unable to create launch intent for notification");
      return;
    }

    PendingIntent contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    Notification notification = new NotificationCompat.Builder(appContext, NotificationChannels.OTHER)
            .setSmallIcon(R.drawable.ic_smsecure)
            .setColor(resolveThemeColor(appContext, R.attr.appColorToolbarBackground))
            .setContentTitle(appContext.getString(R.string.DualSimUtil__new_sim_card_detected))
            .setContentText(appContext.getString(R.string.DualSimUtil__a_new_key_has_been_generated))
            .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(appContext.getString(
                            R.string.DualSimUtil__a_new_key_has_been_generated_for_that_new_sim_card)))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .build();

    ServiceUtil.getNotificationManager(appContext).notify(NOTIFICATION_ID, notification);
  }
}