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

package org.jimvixx.smsecure.logsubmit;

import android.app.Application;
import androidx.lifecycle.SavedStateHandle;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.jimvixx.smsecure.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SubmitLogViewModelTest {
  @Test public void repeatedStartDoesNotRepeatUpload() throws Exception {
    AtomicInteger uploads = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    SubmitLogViewModel model = model(new SavedStateHandle(), () -> {
      assertTrue(release.await(5, TimeUnit.SECONDS)); return "test log";
    }, text -> { uploads.incrementAndGet(); return LogUploadResult.success("test", "id", "key", 1); }, true);
    onMain(() -> { model.start(); model.start(); });
    release.countDown();
    await(model);
    onMain(model::start);
    assertEquals(1, uploads.get());
    assertEquals(R.string.log_submit__log_uploaded_status, state(model).status);
    onMain(model::onCleared);
  }

  @Test public void restoredProcessNeverRepeatsUpload() throws Exception {
    HashMap<String,Object> saved = new HashMap<>();
    saved.put("started", true);
    SubmitLogViewModel model = model(new SavedStateHandle(saved), () -> "test log",
            text -> { throw new AssertionError("Unexpected upload after restore"); }, true);
    onMain(model::start); await(model);
    assertEquals(R.string.log_submit__interrupted, state(model).status);
    assertNull(model.logs);
    onMain(model::onCleared);
  }

  @Test public void restoredReportKeepsOriginalLogAndInfo() throws Exception {
    HashMap<String,Object> saved = new HashMap<>();
    String name = "submitted-log-" + java.util.UUID.randomUUID() + ".txt";
    java.io.File file = new java.io.File(InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getCacheDir(), name);
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
      out.write("original report".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    saved.put("started", true);
    saved.put("cacheName", name);
    saved.put("status", R.string.log_submit__log_uploaded_status);
    saved.put("info", "original upload info");
    SubmitLogViewModel model = model(new SavedStateHandle(saved),
            () -> { throw new AssertionError("Must not collect another report"); },
            text -> { throw new AssertionError("Must not repeat upload"); }, true);
    onMain(model::start); await(model);
    assertEquals("original report", model.logs);
    assertEquals("original upload info", state(model).info);
    onMain(model::onCleared);
    assertFalse(file.exists());
  }

  @Test public void offlineStillAllowsExport() throws Exception {
    SubmitLogViewModel model = model(new SavedStateHandle(), () -> "test log",
            text -> { throw new AssertionError("Unexpected offline upload"); }, false);
    onMain(model::start); await(model);
    assertEquals(R.string.log_submit__no_internet, state(model).status);
    assertEquals("test log", model.logs);
    onMain(model::onCleared);
  }

  @Test public void serverAndConnectionErrorsHaveDistinctMessages() throws Exception {
    for (boolean server : new boolean[]{true, false}) {
      SubmitLogViewModel model = model(new SavedStateHandle(), () -> "test log",
              text -> LogUploadResult.error("private technical details", server), true);
      onMain(model::start); await(model);
      assertEquals(server ? R.string.log_submit__log_server_error : R.string.log_submit__log_server_unreachable,
              state(model).status);
      assertNull(state(model).info);
      assertFalse(state(model).messageAvailable);
      onMain(model::onCleared);
    }
  }

  @Test public void uploadResponseSupportsOptionalTokenAndLegacyClients() throws Exception {
    String token = "A".repeat(43);
    LogUploadResult extended = LogUploadService.parseSuccessResponse(
            "{\"id\":\"report\",\"key\":\"logs/key.zip\",\"size\":7,\"messageToken\":\""
                    + token + "\"}");
    assertEquals(token, extended.messageToken);
    assertEquals("report", extended.reportId);
    assertEquals("logs/key.zip", extended.objectKey);

    LogUploadResult legacy = LogUploadService.parseSuccessResponse(
            "{\"id\":\"old-report\",\"key\":\"logs/old.zip\",\"size\":9}");
    assertNull(legacy.messageToken);
    assertEquals(9, legacy.size);
  }

  @Test public void successfulUploadWithTokenDisplaysMessageFormAndSendsOnlyMessage() throws Exception {
    AtomicInteger uploads = new AtomicInteger();
    AtomicInteger messages = new AtomicInteger();
    SavedStateHandle saved = new SavedStateHandle();
    String token = "B".repeat(43);
    String[] sent = new String[4];
    SubmitLogViewModel model = model(saved, () -> "test log", text -> {
      uploads.incrementAndGet();
      return LogUploadResult.success("test", "report-id", "object-key", token, 1);
    }, true, (reportId, objectKey, actualToken, message) -> {
      messages.incrementAndGet();
      sent[0] = reportId;
      sent[1] = objectKey;
      sent[2] = actualToken;
      sent[3] = message;
      return LogMessageSubmissionService.Result.success();
    });
    onMain(model::start);
    await(model);

    assertTrue(state(model).messageAvailable);
    onMain(() -> model.sendMessage("additional context"));
    awaitMessage(model);
    assertEquals(1, uploads.get());
    assertEquals(1, messages.get());
    assertArrayEquals(new String[]{"report-id", "object-key", token, "additional context"}, sent);
    assertTrue(state(model).messageSent);
    assertTrue(state(model).messageAvailable);
    assertNull(saved.get("messageToken"));
    onMain(model::onCleared);
  }

  @Test public void messageRequestContainsReportReferenceTokenAndText() throws Exception {
    JSONObject body = new JSONObject(LogMessageSubmissionService.requestBody(
            "report-id", "logs/2026-01-01/report.zip", "token", "user text"));
    assertEquals("report-id", body.getString("reportId"));
    assertEquals("logs/2026-01-01/report.zip", body.getString("objectKey"));
    assertEquals("token", body.getString("token"));
    assertEquals("user text", body.getString("message"));
  }

  @Test public void emptyMessageIsNotSubmitted() throws Exception {
    AtomicInteger messages = new AtomicInteger();
    SubmitLogViewModel model = model(new SavedStateHandle(), () -> "test log",
            text -> LogUploadResult.success("test", "report-id", "object-key", "C".repeat(43), 1),
            true, (reportId, objectKey, token, message) -> {
              messages.incrementAndGet();
              return LogMessageSubmissionService.Result.success();
            });
    onMain(model::start);
    await(model);
    onMain(() -> model.sendMessage(" \n\t "));
    assertEquals(0, messages.get());
    assertEquals(R.string.log_submit__message_empty, state(model).messageStatus);
    onMain(model::onCleared);
  }

  @Test public void failedMessageKeepsDraftAndRetryDoesNotReuploadLog() throws Exception {
    AtomicInteger uploads = new AtomicInteger();
    AtomicInteger messages = new AtomicInteger();
    SubmitLogViewModel model = model(new SavedStateHandle(), () -> "test log", text -> {
      uploads.incrementAndGet();
      return LogUploadResult.success("test", "report-id", "object-key", "D".repeat(43), 1);
    }, true, (reportId, objectKey, token, message) -> {
      if (messages.getAndIncrement() == 0) {
        return LogMessageSubmissionService.Result.error("server error", true);
      }
      return LogMessageSubmissionService.Result.success();
    });
    onMain(model::start);
    await(model);
    onMain(() -> model.sendMessage("preserve this draft"));
    awaitMessage(model);
    assertEquals("preserve this draft", model.getMessageText());
    assertFalse(state(model).messageSent);
    onMain(() -> model.sendMessage(model.getMessageText()));
    awaitMessage(model);
    assertEquals(1, uploads.get());
    assertEquals(2, messages.get());
    assertTrue(state(model).messageSent);
    onMain(model::onCleared);
  }

  @Test public void restoredStateKeepsDraftAndMessageAvailabilityWithoutUploadingAgain() throws Exception {
    HashMap<String,Object> saved = new HashMap<>();
    saved.put("started", true);
    saved.put("status", R.string.log_submit__log_uploaded_status);
    saved.put("info", "original upload info");
    saved.put("reportId", "report-id");
    saved.put("objectKey", "object-key");
    saved.put("messageToken", "E".repeat(43));
    saved.put("messageText", "restored draft");
    SubmitLogViewModel model = model(new SavedStateHandle(saved),
            () -> { throw new AssertionError("Must not collect another report"); },
            text -> { throw new AssertionError("Must not repeat upload"); }, true);
    onMain(model::start);
    await(model);
    assertTrue(state(model).messageAvailable);
    assertEquals("restored draft", model.getMessageText());
    assertEquals("original upload info", state(model).info);
    onMain(model::onCleared);
  }

  private SubmitLogViewModel model(SavedStateHandle saved, java.util.concurrent.Callable<String> collect,
          java.util.function.Function<String,LogUploadResult> upload, boolean connected) {
    return model(saved, collect, upload, connected, (reportId, objectKey, token, message) ->
            LogMessageSubmissionService.Result.success());
  }
  private SubmitLogViewModel model(SavedStateHandle saved, java.util.concurrent.Callable<String> collect,
          java.util.function.Function<String,LogUploadResult> upload, boolean connected,
          SubmitLogViewModel.MessageSender messageSender) {
    return new SubmitLogViewModel((Application) InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getApplicationContext(), saved, collect, upload, () -> connected,
            messageSender);
  }
  private void onMain(Runnable task) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
  }

  private static SubmitLogViewModel.State state(SubmitLogViewModel model) {
    SubmitLogViewModel.State state = model.state.getValue();
    if (state == null) {
      throw new AssertionError("SubmitLogViewModel state is unavailable");
    }
    return state;
  }

  private void await(SubmitLogViewModel model) throws Exception {
    for (int i=0;i<100;i++) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      SubmitLogViewModel.State state = model.state.getValue();
      if (state != null && !state.busy) return;
      Thread.sleep(50);
    }
    fail("Operation did not complete");
  }
  private void awaitMessage(SubmitLogViewModel model) throws Exception {
    for (int i=0;i<100;i++) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      SubmitLogViewModel.State state = model.state.getValue();
      if (state != null && !state.messageBusy) return;
      Thread.sleep(50);
    }
    fail("Message operation did not complete");
  }
}
