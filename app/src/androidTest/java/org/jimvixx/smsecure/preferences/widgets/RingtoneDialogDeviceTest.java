package org.jimvixx.smsecure.preferences.widgets;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** Exercises the real device ringtone provider without saving a preference. */
@RunWith(AndroidJUnit4.class)
public class RingtoneDialogDeviceTest {
  private static final String TAG = "ringtone-device-test";

  @Test public void defaultAndSilentUrisNeverReachProviderLookup() {
    android.media.RingtoneManager manager = new android.media.RingtoneManager(
            InstrumentationRegistry.getInstrumentation().getTargetContext()) {
      @Override public int getRingtonePosition(android.net.Uri uri) {
        throw new AssertionError("Symbolic/default URI must bypass provider lookup");
      }
    };
    for (int type : new int[]{android.media.RingtoneManager.TYPE_NOTIFICATION,
            android.media.RingtoneManager.TYPE_RINGTONE, android.media.RingtoneManager.TYPE_ALARM}) {
      android.net.Uri uri = android.media.RingtoneManager.getDefaultUri(type);
      assertEquals(0, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, true, true));
      assertEquals(0, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, false, true));
      assertEquals(-1, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, false, false));
    }
    assertEquals(1, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, null, true, true));
    assertEquals(-1, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, null, true, false));
  }

  @Test public void malformedProviderIdFallsBackWithoutCrashing() {
    android.media.RingtoneManager manager = new android.media.RingtoneManager(
            InstrumentationRegistry.getInstrumentation().getTargetContext()) {
      @Override public int getRingtonePosition(android.net.Uri uri) {
        throw new NumberFormatException("Synthetic provider ID failure");
      }
    };
    android.net.Uri uri = android.net.Uri.parse("content://media/external/audio/media/not-a-number");
    assertEquals(1, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, true, true));
    assertEquals(-1, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, true, false));
  }

  @Test public void concreteRingtoneRetainsRowOffsets() {
    android.media.RingtoneManager manager = new android.media.RingtoneManager(
            InstrumentationRegistry.getInstrumentation().getTargetContext()) {
      @Override public int getRingtonePosition(android.net.Uri uri) { return 3; }
    };
    android.net.Uri uri = android.net.Uri.parse("content://media/external/audio/media/42");
    assertEquals(5, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, true, true));
    assertEquals(4, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, true, false));
    assertEquals(3, RingtonePreferenceDialogFragmentCompat.resolveSelectedIndex(manager, uri, false, false));
  }

  @Test public void snapshotSurvivesRecreationAndRepeatedOpening() throws Exception {
    Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
            ApplicationPreferencesActivity.class);
    intent.putExtra(ApplicationPreferencesActivity.EXTRA_START_CATEGORY,
            "preference_category_notifications");
    try (ActivityScenario<ApplicationPreferencesActivity> scenario = ActivityScenario.launch(intent)) {
      for (int i = 0; i < 3; i++) {
        scenario.onActivity(activity -> {
          activity.getSupportFragmentManager().executePendingTransactions();
          RingtonePreferenceDialogFragmentCompat fragment =
                  RingtonePreferenceDialogFragmentCompat.newInstance("pref_key_ringtone");
          fragment.showNow(activity.getSupportFragmentManager(), TAG);
          verifySnapshot(fragment);
        });
        rotate(scenario, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        rotate(scenario, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                android.content.res.Configuration.ORIENTATION_PORTRAIT);
        scenario.recreate();
        scenario.onActivity(activity -> {
          RingtonePreferenceDialogFragmentCompat fragment =
                  (RingtonePreferenceDialogFragmentCompat) activity.getSupportFragmentManager()
                          .findFragmentByTag(TAG);
          assertNotNull(fragment);
          verifySnapshot(fragment);
          AlertDialog dialog = (AlertDialog) fragment.requireDialog();
          for (int position = 0; position < 3; position++) {
            if (position < dialog.getListView().getCount()) {
              dialog.getListView().performItemClick(null, position,
                      dialog.getListView().getItemIdAtPosition(position));
            }
          }
          fragment.dismissNow();
        });
      }
    }
  }

  private static void rotate(ActivityScenario<ApplicationPreferencesActivity> scenario,
                             int requested, int expected) throws Exception {
    scenario.onActivity(activity -> activity.setRequestedOrientation(requested));
    java.util.concurrent.atomic.AtomicBoolean rotated = new java.util.concurrent.atomic.AtomicBoolean();
    for (int attempt = 0; attempt < 50; attempt++) {
      scenario.onActivity(activity -> rotated.set(
              activity.getResources().getConfiguration().orientation == expected));
      if (rotated.get()) break;
      Thread.sleep(100);
    }
    assertTrue("Requested orientation not applied", rotated.get());
    scenario.onActivity(activity -> verifySnapshot((RingtonePreferenceDialogFragmentCompat)
            activity.getSupportFragmentManager().findFragmentByTag(TAG)));
  }

  private static void verifySnapshot(RingtonePreferenceDialogFragmentCompat fragment) {
    try {
      Field displayField = RingtonePreferenceDialogFragmentCompat.class.getDeclaredField("cursor");
      Field sourceField = RingtonePreferenceDialogFragmentCompat.class.getDeclaredField("ringtoneSourceCursor");
      displayField.setAccessible(true);
      sourceField.setAccessible(true);
      Cursor display = (Cursor) displayField.get(fragment);
      Cursor source = (Cursor) sourceField.get(fragment);
      assertTrue(display instanceof MatrixCursor);
      assertNotNull(source);
      assertFalse(source.isClosed());
      int extras = display.getCount() - source.getCount();
      assertEquals(2, extras);
      int originalPosition = source.getPosition();
      source.moveToPosition(-1);
      while (source.moveToNext()) {
        assertTrue(display.moveToPosition(source.getPosition() + extras));
        assertEquals(source.getLong(0), display.getLong(0));
        assertEquals(source.getString(1), display.getString(1));
      }
      source.moveToPosition(originalPosition);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
