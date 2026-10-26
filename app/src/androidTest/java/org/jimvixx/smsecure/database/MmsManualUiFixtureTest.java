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

package org.jimvixx.smsecure.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;

import org.jimvixx.smsecure.crypto.EncryptingPartOutputStream;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Explicit host-invoked fixture loader for manual MMS conversation UI review. */
@RunWith(AndroidJUnit4.class)
public final class MmsManualUiFixtureTest {
  private static final String AVD_NAME = "silence-counterpart";
  private static final String FIXTURE_FILE = "mms-manual-ui-fixture.txt";
  private static final String CANONICAL_ADDRESS_TABLE = "canonical_addresses";
  private static final String FIXTURE_PREFIX = "SMSecure MMS TEST ";
  private static final String MMS_TRANSACTION_PREFIX = "SMSecure-MMS-TEST-";
  private static final long BASE_INBOX = 20L;
  private static final long BASE_SENT = 23L;
  private static final long ENCRYPTED_INBOX = 0x80000014L;

  @Test public void loadFixture() throws Exception {
    requireExplicitAction("load");
    Context context = targetContext();
    requireDisposableEmulator();
    requireDebuggableTarget(context);
    verifyExistingDatabases(context);

    File marker = fixtureMarker(context);
    if (fixtureMarkerExists(marker)) {
      fail("A manual MMS fixture is already registered; run cleanupFixture first");
    }

    String token = UUID.randomUUID().toString().replace("-", "");
    List<String> addresses = syntheticAddresses(token);
    CanonicalAddressDatabase canonical = CanonicalAddressDatabase.getInstance(context);
    for (String address : addresses) {
      if (canonical.findCanonicalAddressId(address) != -1L) {
        fail("Synthetic recipient collision; no database rows were changed");
      }
    }
    List<Long> emptyThreadIds = new ArrayList<>(Collections.nCopies(5, -1L));
    List<Long> emptyThreadDates = new ArrayList<>(Collections.nCopies(5, -1L));
    MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
    if (masterSecret == null) {
      fail("Unlock SMSecure before loading the synthetic image fixture");
    }
    byte[] imageBytes = createSyntheticImage();
    writeMarker(marker, token, addresses, emptyThreadIds, emptyThreadDates);
    File incomingImage = writeEncryptedFixturePart(context, token, "indata", imageBytes, masterSecret);
    File incomingThumbnail = writeEncryptedFixturePart(context, token, "inthumb", imageBytes, masterSecret);
    File outgoingImage = writeEncryptedFixturePart(context, token, "outdata", imageBytes, masterSecret);
    File outgoingThumbnail = writeEncryptedFixturePart(context, token, "outthumb", imageBytes, masterSecret);
    int imageSize = imageBytes.length;
    Arrays.fill(imageBytes, (byte) 0);

    DatabaseFactory.getInstance(context);
    List<Long> recipientIds = new ArrayList<>();
    for (String address : addresses) {
      recipientIds.add(canonical.getCanonicalAddressId(address));
    }

    SQLiteDatabase database = DatabaseFactory.getDatabaseHelper(context).getWritableDatabase();
    ThreadDatabase threads = DatabaseFactory.getThreadDatabase(context);
    database.beginTransaction();
    try {
      List<Long> threadIds = new ArrayList<>();
      for (long recipientId : recipientIds) {
        Recipients recipients = RecipientFactory.getRecipientsForIds(context,
            new long[]{recipientId}, false);
        if (threads.getThreadIdIfExistsFor(recipients) != -1L) {
          throw new IllegalStateException("Synthetic recipient already has a conversation");
        }
        threadIds.add(threads.getThreadIdFor(recipients));
      }

      long firstMessageId = nextFixtureMessageId(database);
      long now = System.currentTimeMillis();
      String markerText = FIXTURE_PREFIX + token;
      String label = FIXTURE_PREFIX + token.substring(0, 8);
      String transactionId = MMS_TRANSACTION_PREFIX + token;

      insertSms(database, firstMessageId, threadIds.get(0), addresses.get(0), now - 60000,
          markerText + "\n" + label + "\nSMS-only incoming test", BASE_INBOX);

      insertMms(database, firstMessageId + 1, threadIds.get(1), addresses.get(1), now - 50000,
          label + "\nMMS-only incoming text", BASE_INBOX, transactionId + "-in");
      insertMms(database, firstMessageId + 2, threadIds.get(1), addresses.get(1), now - 40000,
          label + "\nMMS-only outgoing text", BASE_SENT, transactionId + "-out");

      insertSms(database, firstMessageId + 3, threadIds.get(2), addresses.get(2), now - 30000,
          markerText + "\n" + label + "\nMixed SMS message", BASE_INBOX);
      insertMms(database, firstMessageId + 3, threadIds.get(2), addresses.get(2), now - 20000,
          label + "\nMixed MMS message", BASE_SENT, transactionId + "-mixed");

      long attachmentId = firstMessageId + 4;
      insertMms(database, attachmentId, threadIds.get(3), addresses.get(3), now - 10000,
          label + "\nIncoming synthetic encrypted image preview", BASE_INBOX,
          transactionId + "-attachment-in");
      insertAttachmentMetadata(database, attachmentId, firstMessageId + 20,
          incomingImage, incomingThumbnail, imageSize);

      long outgoingAttachmentId = firstMessageId + 6;
      insertMms(database, outgoingAttachmentId, threadIds.get(3), addresses.get(3), now - 5000,
          label + "\nOutgoing synthetic encrypted image preview", BASE_SENT,
          transactionId + "-attachment-out");
      insertAttachmentMetadata(database, outgoingAttachmentId, firstMessageId + 21,
          outgoingImage, outgoingThumbnail, imageSize);

      insertMms(database, firstMessageId + 5, threadIds.get(4), addresses.get(4), now,
          null, ENCRYPTED_INBOX, transactionId + "-encrypted");

      for (long threadId : threadIds) {
        threads.update(threadId, false);
      }
      List<Long> threadDates = new ArrayList<>();
      for (long threadId : threadIds) threadDates.add(getThreadDate(database, threadId));
      writeMarker(marker, token, addresses, threadIds, threadDates);
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }

    assertTrue(marker.isFile());
    assertEquals(5, recipientIds.size());
  }

  @Test public void cleanupFixture() throws Exception {
    requireExplicitAction("cleanup");
    Context context = targetContext();
    requireDisposableEmulator();
    requireDebuggableTarget(context);
    verifyExistingDatabases(context);
    File marker = fixtureMarker(context);
    if (!fixtureMarkerExists(marker)) {
      fail("No registered manual MMS fixture exists");
    }

    Fixture fixture = readMarker(marker);
    DatabaseFactory.getInstance(context);
    SQLiteDatabase database = DatabaseFactory.getDatabaseHelper(context).getWritableDatabase();
    List<Long> affectedThreads = new ArrayList<>();
    database.beginTransaction();
    try {
      String smsMarker = FIXTURE_PREFIX + fixture.token;
      try (Cursor cursor = database.rawQuery(
          "SELECT DISTINCT thread_id FROM sms WHERE instr(body, ?) > 0",
          new String[]{smsMarker})) {
        while (cursor.moveToNext()) affectedThreads.add(cursor.getLong(0));
      }

      String mmsMarker = MMS_TRANSACTION_PREFIX + fixture.token + "%";
      for (long threadId : fixture.threadIds) {
        if (threadId > 0 && !affectedThreads.contains(threadId)) affectedThreads.add(threadId);
      }

      database.delete("sms", "instr(body, ?) > 0", new String[]{smsMarker});
      List<Long> fixtureMmsIds = new ArrayList<>();
      try (Cursor cursor = database.rawQuery("SELECT _id FROM mms WHERE tr_id LIKE ?",
          new String[]{mmsMarker})) {
        while (cursor.moveToNext()) fixtureMmsIds.add(cursor.getLong(0));
      }
      for (long mmsId : fixtureMmsIds) {
        database.delete("part", "mid = ?", new String[]{String.valueOf(mmsId)});
        database.delete("mms_addresses", "mms_id = ?", new String[]{String.valueOf(mmsId)});
      database.delete("mms", "_id = ? AND tr_id LIKE ?",
          new String[]{String.valueOf(mmsId), mmsMarker});
      }

      CanonicalAddressDatabase canonical = CanonicalAddressDatabase.getInstance(context);
      ThreadDatabase threads = DatabaseFactory.getThreadDatabase(context);
      for (long threadId : affectedThreads) {
        int fixtureIndex = fixture.threadIds.indexOf(threadId);
        if (fixtureIndex < 0) continue;
        long recipientId = canonical.findCanonicalAddressId(fixture.addresses.get(fixtureIndex));
        if (!hasMessages(database, threadId) && !hasDrafts(database, threadId) &&
            isFixtureThread(database, threadId, recipientId,
                fixture.threadDates.get(fixtureIndex))) {
          database.delete("thread", "_id = ?", new String[]{String.valueOf(threadId)});
        } else if (hasMessages(database, threadId)) {
          threads.update(threadId, false);
        }
      }

      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }

    deleteFixtureAttachmentFiles(context, fixture.token);
    removeUnreferencedCanonicalAddresses(context, fixture.addresses);
    new AtomicFile(marker).delete();
  }

  private Context targetContext() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private void requireExplicitAction(String expectedAction) {
    String action = InstrumentationRegistry.getArguments().getString("mms_fixture_action");
    Assume.assumeTrue("Fixture operations require an explicit mms_fixture_action argument",
        expectedAction.equals(action));
  }

  private void requireDisposableEmulator() throws Exception {
    if (Build.VERSION.SDK_INT < 24 ||
        !(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("qemu"))) {
      throw new SecurityException("Manual MMS fixtures are restricted to Android emulators");
    }

    ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation().executeShellCommand("getprop ro.boot.qemu.avd_name");
    String avdName;
    try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[128];
      int read;
      while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
      avdName = output.toString(StandardCharsets.UTF_8.name()).trim();
    } finally {
      descriptor.close();
    }
    if (!AVD_NAME.equals(avdName)) {
      throw new SecurityException("Refusing to modify application data outside the " + AVD_NAME + " AVD");
    }
  }

  private void requireDebuggableTarget(Context context) {
    if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
      throw new SecurityException("Manual MMS fixtures are available only in Debug installations");
    }
  }

  private void verifyExistingDatabases(Context context) {
    File messagesFile = context.getDatabasePath("messages.db");
    File addressFile = context.getDatabasePath("canonical_address.db");
    if (!messagesFile.isFile() || !addressFile.isFile()) {
      throw new IllegalStateException("Fixture requires existing SMSecure databases and will not create them");
    }
    verifyDatabaseVersion(messagesFile, 38,
        new String[]{"sms", "mms", "mms_addresses", "part", "thread", "drafts",
            "recipient_preferences", "identities"});
    verifyDatabaseVersion(addressFile, 1, new String[]{CANONICAL_ADDRESS_TABLE});
  }

  private void verifyDatabaseVersion(File file, int expectedVersion, String[] requiredTables) {
    SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
        SQLiteDatabase.OPEN_READONLY);
    try {
      try (Cursor cursor = database.rawQuery("PRAGMA user_version", null)) {
        if (!cursor.moveToFirst() || cursor.getInt(0) != expectedVersion) {
          throw new IllegalStateException("Fixture database schema version is not supported");
        }
      }
      for (String table : requiredTables) {
        try (Cursor cursor = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", new String[]{table})) {
          if (!cursor.moveToFirst()) {
            throw new IllegalStateException("Fixture database is missing a required table");
          }
        }
      }
    } finally {
      database.close();
    }
  }

  private File fixtureMarker(Context context) {
    return new File(context.getFilesDir(), FIXTURE_FILE);
  }

  private boolean fixtureMarkerExists(File marker) {
    return marker.isFile() || new File(marker.getPath() + ".bak").isFile();
  }

  private List<String> syntheticAddresses(String token) {
    List<String> addresses = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      long suffix = Long.parseLong(token.substring(i * 6, i * 6 + 6), 16) % 10000000L;
      addresses.add("+1555" + String.format(java.util.Locale.US, "%07d", suffix));
    }
    if (addresses.stream().distinct().count() != addresses.size()) {
      throw new IllegalStateException("Unable to generate distinct synthetic recipients");
    }
    return addresses;
  }

  private void writeMarker(File marker, String token, List<String> addresses,
                           List<Long> threadIds, List<Long> threadDates) throws IOException {
    AtomicFile atomicFile = new AtomicFile(marker);
    FileOutputStream output = atomicFile.startWrite();
    try {
      StringBuilder contents = new StringBuilder(token).append('\n');
      for (String address : addresses) contents.append(address).append('\n');
      for (long threadId : threadIds) contents.append(threadId).append('\n');
      for (long threadDate : threadDates) contents.append(threadDate).append('\n');
      output.write(contents.toString().getBytes(StandardCharsets.UTF_8));
      output.getFD().sync();
      atomicFile.finishWrite(output);
    } catch (IOException | RuntimeException exception) {
      atomicFile.failWrite(output);
      throw exception;
    }
  }

  private Fixture readMarker(File marker) throws IOException {
    byte[] bytes;
    try (FileInputStream input = new AtomicFile(marker).openRead();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[256];
      int read;
      while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
      bytes = output.toByteArray();
    }
    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\n");
    if (lines.length != 16 || !lines[0].matches("[0-9a-f]{32}")) {
      throw new IOException("Fixture marker is invalid; no database changes were made");
    }
    List<String> addresses = new ArrayList<>();
    Collections.addAll(addresses, lines[1], lines[2], lines[3], lines[4], lines[5]);
    if (!addresses.equals(syntheticAddresses(lines[0]))) {
      throw new IOException("Fixture marker recipient validation failed; no database changes were made");
    }
    List<Long> threadIds = new ArrayList<>();
    List<Long> threadDates = new ArrayList<>();
    try {
      for (int i = 6; i < 11; i++) threadIds.add(Long.parseLong(lines[i]));
      for (int i = 11; i < 16; i++) threadDates.add(Long.parseLong(lines[i]));
    } catch (NumberFormatException exception) {
      throw new IOException("Fixture marker thread validation failed; no database changes were made", exception);
    }
    return new Fixture(lines[0], addresses, threadIds, threadDates);
  }

  private long nextFixtureMessageId(SQLiteDatabase database) {
    try (Cursor cursor = database.rawQuery(
        "SELECT MAX(value) FROM (SELECT COALESCE(MAX(_id), 0) AS value FROM sms " +
            "UNION ALL SELECT COALESCE(MAX(_id), 0) AS value FROM mms " +
            "UNION ALL SELECT COALESCE(MAX(_id), 0) AS value FROM part)", null)) {
      if (!cursor.moveToFirst() || cursor.getLong(0) > Long.MAX_VALUE - 1000) {
        throw new IllegalStateException("Unable to allocate synthetic message IDs safely");
      }
      return cursor.getLong(0) + 1000;
    }
  }

  private void insertSms(SQLiteDatabase database, long id, long threadId, String address,
                         long timestamp, String body, long type) {
    ContentValues values = new ContentValues();
    values.put("_id", id);
    values.put("thread_id", threadId);
    values.put("address", address);
    values.put("address_device_id", 1);
    values.put("date", timestamp);
    values.put("date_sent", timestamp);
    values.put("protocol", 0);
    values.put("read", 1);
    values.put("status", -1);
    values.put("type", type);
    values.put("body", body);
    values.put("subscription_id", -1);
    if (database.insertOrThrow("sms", null, values) != id) {
      throw new IllegalStateException("Synthetic SMS insert failed");
    }
  }

  private void insertMms(SQLiteDatabase database, long id, long threadId, String address,
                         long timestamp, String body, long messageBox, String transactionId) {
    ContentValues values = new ContentValues();
    values.put("_id", id);
    values.put("thread_id", threadId);
    values.put("date", timestamp);
    values.put("date_received", timestamp);
    values.put("msg_box", messageBox);
    values.put("read", 1);
    values.put("m_id", transactionId);
    values.put("body", body);
    values.put("part_count", 0);
    values.put("ct_t", "application/vnd.wap.multipart.related");
    values.put("address", address);
    values.put("m_type", 132);
    values.put("v", 18);
    values.put("m_size", body == null ? 0 : body.length());
    values.put("st", 0);
    values.put("tr_id", transactionId);
    values.put("subscription_id", -1);
    database.insertOrThrow("mms", null, values);

    ContentValues addressValues = new ContentValues();
    addressValues.put("mms_id", id);
    addressValues.put("address", address);
    addressValues.put("type", (messageBox & 0xFF) == BASE_SENT ? 151 : 137);
    addressValues.put("address_charset", 106);
    database.insertOrThrow("mms_addresses", null, addressValues);
  }

  private void insertAttachmentMetadata(SQLiteDatabase database, long mmsId, long partId,
                                        File original, File thumbnail, int imageSize) {
    ContentValues values = new ContentValues();
    values.put("_id", partId);
    values.put("mid", mmsId);
    values.put("seq", 0);
    values.put("ct", "image/png");
    values.put("name", "synthetic-photo.png");
    values.put("_data", original.getAbsolutePath());
    values.put("thumbnail", thumbnail.getAbsolutePath());
    values.put("unique_id", partId);
    values.put("encrypted", 1);
    values.put("data_size", imageSize);
    values.put("file_name", "synthetic-photo.png");
    database.insertOrThrow("part", null, values);
    ContentValues update = new ContentValues();
    update.put("part_count", 1);
    database.update("mms", update, "_id = ?", new String[]{String.valueOf(mmsId)});
  }

  private byte[] createSyntheticImage() throws IOException {
    Bitmap bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888);
    for (int y = 0; y < bitmap.getHeight(); y++) {
      for (int x = 0; x < bitmap.getWidth(); x++) {
        bitmap.setPixel(x, y, ((x / 12 + y / 12) % 2 == 0) ? Color.rgb(40, 120, 210) :
                Color.rgb(245, 190, 60));
      }
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    boolean compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    bitmap.eraseColor(Color.TRANSPARENT);
    bitmap.recycle();
    if (!compressed) throw new IOException("Unable to generate synthetic image fixture");
    return output.toByteArray();
  }

  private File writeEncryptedFixturePart(Context context, String token, String suffix,
                                         byte[] plaintext, MasterSecret masterSecret)
          throws IOException {
    File partsDirectory = context.getDir("parts", Context.MODE_PRIVATE).getCanonicalFile();
    File file = new File(partsDirectory, "part" + token + suffix + ".mms");
    if (!partsDirectory.equals(file.getCanonicalFile().getParentFile()) || file.exists()) {
      throw new IOException("Synthetic image fixture path is not available");
    }
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(file, masterSecret)) {
      output.write(plaintext);
    }
    return file;
  }

  private void deleteFixtureAttachmentFiles(Context context, String token) throws IOException {
    File partsDirectory = context.getDir("parts", Context.MODE_PRIVATE).getCanonicalFile();
    for (String suffix : new String[]{"indata", "inthumb", "outdata", "outthumb"}) {
      File file = new File(partsDirectory, "part" + token + suffix + ".mms");
      if (!partsDirectory.equals(file.getAbsoluteFile().getParentFile()) ||
          !file.getName().matches("part" + token + "(?:indata|inthumb|outdata|outthumb)\\.mms") ||
          !file.getCanonicalFile().equals(file)) {
        throw new IOException("Refusing to remove an unexpected synthetic attachment path");
      }
      if (file.exists() && (!file.isFile() || !file.delete())) {
        throw new IOException("Unable to remove synthetic image fixture file");
      }
    }
  }

  private boolean hasMessages(SQLiteDatabase database, long threadId) {
    return count(database, "SELECT (SELECT COUNT(*) FROM sms WHERE thread_id = ?) + " +
        "(SELECT COUNT(*) FROM mms WHERE thread_id = ?)", threadId, threadId) > 0;
  }

  private boolean hasDrafts(SQLiteDatabase database, long threadId) {
    return count(database, "SELECT COUNT(*) FROM drafts WHERE thread_id = ?", threadId) > 0;
  }

  private boolean isFixtureThread(SQLiteDatabase database, long threadId, long recipientId,
                                  long expectedDate) {
    String expected = null;
    long date = -1L;
    try (Cursor cursor = database.query("thread", new String[]{"recipient_ids", "date"}, "_id = ?",
        new String[]{String.valueOf(threadId)}, null, null, null)) {
      if (cursor.moveToFirst()) {
        expected = cursor.getString(0);
        date = cursor.getLong(1);
      }
    }
    return recipientId > 0 && expected != null &&
        String.valueOf(recipientId).equals(expected) && date == expectedDate;
  }

  private long getThreadDate(SQLiteDatabase database, long threadId) {
    try (Cursor cursor = database.query("thread", new String[]{"date"}, "_id = ?",
        new String[]{String.valueOf(threadId)}, null, null, null)) {
      if (!cursor.moveToFirst()) throw new IllegalStateException("Fixture thread disappeared");
      return cursor.getLong(0);
    }
  }

  private int count(SQLiteDatabase database, String sql, long... args) {
    String[] stringArgs = new String[args.length];
    for (int i = 0; i < args.length; i++) stringArgs[i] = String.valueOf(args[i]);
    try (Cursor cursor = database.rawQuery(sql, stringArgs)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private void removeUnreferencedCanonicalAddresses(Context context, List<String> addresses) {
    SQLiteDatabase messages = DatabaseFactory.getDatabaseHelper(context).getReadableDatabase();
    SQLiteDatabase canonical = SQLiteDatabase.openDatabase(
        context.getDatabasePath("canonical_address.db").getAbsolutePath(), null,
        SQLiteDatabase.OPEN_READWRITE);
    boolean changed = false;
    try {
      for (String address : addresses) {
        long id = CanonicalAddressDatabase.getInstance(context).findCanonicalAddressId(address);
        if (id == -1L) continue;
        String storedAddress = CanonicalAddressDatabase.getInstance(context).getAddressFromId(id);
        if (hasExternalRecipientReference(messages, id, address, storedAddress)) continue;
        int removed = canonical.delete(CANONICAL_ADDRESS_TABLE, "_id = ? AND address = ?",
            new String[]{String.valueOf(id), storedAddress});
        changed |= removed == 1;
      }
    } finally {
      canonical.close();
    }
    if (changed) {
      CanonicalAddressDatabase.getInstance(context).reset(context);
      RecipientFactory.clearCache();
    }
  }

  private boolean hasExternalRecipientReference(SQLiteDatabase database, long recipientId,
                                               String address, String storedAddress) {
    String recipient = String.valueOf(recipientId);
    if (count(database, "SELECT COUNT(*) FROM thread WHERE recipient_ids = ?", recipientId) > 0 ||
        countTwoText(database, "SELECT COUNT(*) FROM sms WHERE address IN (?, ?)", address, storedAddress) > 0 ||
        countTwoText(database, "SELECT COUNT(*) FROM mms WHERE address IN (?, ?)", address, storedAddress) > 0 ||
        countTwoText(database, "SELECT COUNT(*) FROM mms_addresses WHERE address IN (?, ?)", address, storedAddress) > 0) {
      return true;
    }
    return countText(database, "SELECT COUNT(*) FROM recipient_preferences WHERE recipient_ids LIKE ?",
        "%" + recipient + "%") > 0 ||
        countText(database, "SELECT COUNT(*) FROM identities WHERE recipient = ?", recipient) > 0;
  }

  private int countText(SQLiteDatabase database, String sql, String arg) {
    try (Cursor cursor = database.rawQuery(sql, new String[]{arg})) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private int countTwoText(SQLiteDatabase database, String sql, String first, String second) {
    try (Cursor cursor = database.rawQuery(sql, new String[]{first, second})) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private static final class Fixture {
    private final String token;
    private final List<String> addresses;
    private final List<Long> threadIds;
    private final List<Long> threadDates;
    private Fixture(String token, List<String> addresses, List<Long> threadIds,
                   List<Long> threadDates) {
      this.token = token;
      this.addresses = addresses;
      this.threadIds = threadIds;
      this.threadDates = threadDates;
    }
  }

}
