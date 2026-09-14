package org.jimvixx.smsecure.logsubmit;

import android.app.Application;
import androidx.lifecycle.SavedStateHandle;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.jimvixx.smsecure.R;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    main(() -> { model.start(); model.start(); });
    release.countDown();
    await(model);
    main(model::start);
    assertEquals(1, uploads.get());
    assertEquals(R.string.log_submit_uploaded_status, model.state.getValue().status);
    main(model::onCleared);
  }

  @Test public void restoredProcessNeverRepeatsUpload() throws Exception {
    HashMap<String,Object> saved = new HashMap<>();
    saved.put("started", true);
    SubmitLogViewModel model = model(new SavedStateHandle(saved), () -> "test log",
            text -> { throw new AssertionError("Unexpected upload after restore"); }, true);
    main(model::start); await(model);
    assertEquals(R.string.log_submit_interrupted, model.state.getValue().status);
    assertNull(model.logs);
    main(model::onCleared);
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
    saved.put("status", R.string.log_submit_uploaded_status);
    saved.put("info", "original upload info");
    SubmitLogViewModel model = model(new SavedStateHandle(saved),
            () -> { throw new AssertionError("Must not collect another report"); },
            text -> { throw new AssertionError("Must not repeat upload"); }, true);
    main(model::start); await(model);
    assertEquals("original report", model.logs);
    assertEquals("original upload info", model.state.getValue().info);
    main(model::onCleared);
    assertFalse(file.exists());
  }

  @Test public void offlineStillAllowsExport() throws Exception {
    SubmitLogViewModel model = model(new SavedStateHandle(), () -> "test log",
            text -> { throw new AssertionError("Unexpected offline upload"); }, false);
    main(model::start); await(model);
    assertEquals(R.string.log_submit_no_internet, model.state.getValue().status);
    assertEquals("test log", model.logs);
    main(model::onCleared);
  }

  @Test public void serverAndConnectionErrorsHaveDistinctMessages() throws Exception {
    for (boolean server : new boolean[]{true, false}) {
      SubmitLogViewModel model = model(new SavedStateHandle(), () -> "test log",
              text -> LogUploadResult.error("private technical details", server), true);
      main(model::start); await(model);
      assertEquals(server ? R.string.log_submit_server_error : R.string.log_submit_unreachable,
              model.state.getValue().status);
      assertNull(model.state.getValue().info);
      main(model::onCleared);
    }
  }

  private SubmitLogViewModel model(SavedStateHandle saved, java.util.concurrent.Callable<String> collect,
          java.util.function.Function<String,LogUploadResult> upload, boolean connected) {
    return new SubmitLogViewModel((Application) InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getApplicationContext(), saved, collect, upload, () -> connected);
  }
  private void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
  private void await(SubmitLogViewModel model) throws Exception {
    for (int i=0;i<100;i++) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      if (model.state.getValue()!=null && !model.state.getValue().busy) return;
      Thread.sleep(50);
    }
    fail("Operation did not complete");
  }
}
