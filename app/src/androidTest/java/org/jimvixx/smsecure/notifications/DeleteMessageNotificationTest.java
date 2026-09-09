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

package org.jimvixx.smsecure.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

@RunWith(AndroidJUnit4.class)
public class DeleteMessageNotificationTest {

  private static final int SUMMARY_NOTIFICATION_ID = 1338;
  private static final String TEST_SENDER = "+420000000056";
  private static final long WAIT_TIMEOUT_MILLIS = 5000;

  private Context context;
  private MasterSecret masterSecret;
  private SmsDatabase smsDatabase;
  private long testThreadId = -1;
  private boolean testSecretInstalled;
  private ServiceConnection serviceConnection;

  @Before
  public void setUp() throws InterruptedException {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    masterSecret = KeyCachingService.getMasterSecret(context);
    if (masterSecret == null) {
      masterSecret = new MasterSecret(
              new SecretKeySpec(new byte[16], "AES"),
              new SecretKeySpec(new byte[16], "HmacSHA1")
      );
      installTestMasterSecret(masterSecret);
      testSecretInstalled = true;
      Thread.sleep(500);
    }
    smsDatabase = DatabaseFactory.getSmsDatabase(context);
  }

  @After
  public void tearDown() {
    if (testThreadId != -1) {
      DatabaseFactory.getThreadDatabase(context).deleteConversation(testThreadId);
    }

    if (masterSecret != null) {
      MessageNotifier.updateNotification(context, masterSecret);
    }

    if (testSecretInstalled) {
      KeyCachingService.clearMasterSecretDirect(context, KeyCachingService.CLEAR_REASON_OTHER);
    }

    if (serviceConnection != null) {
      context.unbindService(serviceConnection);
    }
  }

  @Test
  public void deleteActionTargetsDisplayedMessageAndRebuildsNotification() throws Exception {
    long oldestMessageId = insertMessage("oldest");
    Thread.sleep(10);
    long displayedLatestMessageId = insertMessage("displayed latest");

    MessageNotifier.updateNotification(context, masterSecret, testThreadId);
    Notification initialNotification = waitForNotification(SUMMARY_NOTIFICATION_ID);
    assertThat(initialNotification.number).isEqualTo(2);
    assertThat(initialNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE).isZero();
    assertUnlockedActions(initialNotification);
    PendingIntent displayedLatestDeleteIntent = getAction(
            initialNotification,
            R.string.Delete
    ).actionIntent;

    MessageNotifier.updateNotification(context, null);
    waitForDeleteAction(false);

    MessageNotifier.updateNotification(context, masterSecret);
    Thread.sleep(10);
    long newlyArrivedMessageId = insertMessage("newly arrived");
    MessageNotifier.updateNotification(context, masterSecret);

    displayedLatestDeleteIntent.send();
    waitUntilMessageDeleted(displayedLatestMessageId);

    assertThat(smsDatabase.getThreadIdForMessage(oldestMessageId)).isEqualTo(testThreadId);
    assertThat(smsDatabase.getThreadIdForMessage(newlyArrivedMessageId)).isEqualTo(testThreadId);

    Notification afterStaleAction = waitForNotificationWithCount(2);
    assertThat(afterStaleAction.flags & Notification.FLAG_ONLY_ALERT_ONCE).isNotZero();
    getAction(afterStaleAction, R.string.Delete).actionIntent.send();
    waitUntilMessageDeleted(newlyArrivedMessageId);

    Notification afterFirstSuccessiveDelete = waitForNotificationWithCount(1);
    assertThat(afterFirstSuccessiveDelete.flags & Notification.FLAG_ONLY_ALERT_ONCE).isNotZero();
    getAction(afterFirstSuccessiveDelete, R.string.Delete).actionIntent.send();
    waitUntilMessageDeleted(oldestMessageId);
    waitUntilNotificationRemoved(SUMMARY_NOTIFICATION_ID);
  }

  private long insertMessage(String body) {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    IncomingTextMessage message = new IncomingTextMessage(
            TEST_SENDER,
            1,
            System.currentTimeMillis(),
            body,
            -1
    );
    Pair<Long, Long> source = database.insertMessageInbox(masterSecret, message);
    Pair<Long, Long> unreadCopy = smsDatabase.copyMessageInbox(source.first);
    smsDatabase.deleteMessage(source.first);
    testThreadId = unreadCopy.second;
    return unreadCopy.first;
  }

  private void installTestMasterSecret(MasterSecret testMasterSecret) throws InterruptedException {
    CountDownLatch connected = new CountDownLatch(1);
    serviceConnection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder service) {
        KeyCachingService.KeySetBinder binder = (KeyCachingService.KeySetBinder) service;
        binder.getService().setMasterSecret(testMasterSecret);
        connected.countDown();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
      }
    };

    boolean binding = context.bindService(
            new Intent(context, KeyCachingService.class),
            serviceConnection,
            Context.BIND_AUTO_CREATE
    );
    assertThat(binding).isTrue();
    assertThat(connected.await(WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
  }

  private void assertUnlockedActions(Notification notification) {
    Notification.Action markRead = getAction(notification, R.string.MessageNotifier_mark_read);
    Notification.Action reply = getAction(notification, R.string.MessageNotifier_reply);
    Notification.Action delete = getAction(notification, R.string.Delete);

    assertThat(markRead.getSemanticAction())
            .isEqualTo(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ);
    assertThat(reply.getSemanticAction()).isEqualTo(Notification.Action.SEMANTIC_ACTION_REPLY);
    assertThat(reply.getRemoteInputs()).isNotEmpty();
    assertThat(delete.getSemanticAction()).isEqualTo(Notification.Action.SEMANTIC_ACTION_DELETE);
  }

  private Notification.Action getAction(Notification notification, int titleResource) {
    Notification.Action action = findAction(notification, titleResource);
    assertThat(action).isNotNull();
    return action;
  }

  @Nullable
  private Notification.Action findAction(Notification notification, int titleResource) {
    if (notification.actions == null) return null;

    String expectedTitle = context.getString(titleResource);
    for (Notification.Action action : notification.actions) {
      if (expectedTitle.contentEquals(action.title)) return action;
    }

    return null;
  }

  private Notification waitForNotificationWithCount(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;

    do {
      Notification notification = findNotification(SUMMARY_NOTIFICATION_ID);
      if (notification != null && notification.number == count) return notification;
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);

    throw new AssertionError("Message notification did not reach count " + count);
  }

  private Notification waitForDeleteAction(boolean expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;

    do {
      Notification notification = findNotification(SUMMARY_NOTIFICATION_ID);
      if (notification != null &&
              (findAction(notification, R.string.Delete) != null) == expected) {
        return notification;
      }
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);

    throw new AssertionError("Delete action presence did not become " + expected);
  }

  private Notification waitForNotification(int notificationId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;

    do {
      Notification notification = findNotification(notificationId);
      if (notification != null) return notification;
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);

    throw new AssertionError("Message notification was not posted");
  }

  private void waitUntilMessageDeleted(long messageId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;

    do {
      if (smsDatabase.getThreadIdForMessage(messageId) == -1) return;
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);

    throw new AssertionError("Message was not deleted: " + messageId);
  }

  private void waitUntilNotificationRemoved(int notificationId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;

    do {
      if (findNotification(notificationId) == null) return;
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);

    throw new AssertionError("Message notification was not removed");
  }

  @Nullable
  private Notification findNotification(int notificationId) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);

    for (StatusBarNotification notification : manager.getActiveNotifications()) {
      if (notification.getId() == notificationId) return notification.getNotification();
    }

    return null;
  }
}
