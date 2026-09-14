package org.jimvixx.smsecure.logsubmit;

import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.jimvixx.smsecure.LogSubmitActivity;
import org.jimvixx.smsecure.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit opt-in: the online mode sends one real diagnostic report. */
@RunWith(AndroidJUnit4.class)
public class SubmitLogDeviceTest {
  @Test public void uploadScreenSurvivesRecreation() throws Exception {
    String mode = InstrumentationRegistry.getArguments().getString("logUploadMode", "");
    assumeTrue(mode.equals("online") || mode.equals("offline"));
    try (ActivityScenario<LogSubmitActivity> scenario = ActivityScenario.launch(LogSubmitActivity.class)) {
      scenario.recreate();
      AtomicReference<String> info = new AtomicReference<>();
      AtomicReference<String> status = new AtomicReference<>();
      for (int i=0;i<100;i++) {
        scenario.onActivity(a -> {
          status.set(((TextView)a.findViewById(R.id.log_submit_status)).getText().toString());
          if (a.findViewById(R.id.log_submit_share_link_button).isEnabled() ||
                  a.getString(R.string.log_submit_no_internet).equals(status.get()))
            info.set(((TextView)a.findViewById(R.id.log_submit_preview)).getText().toString());
        });
        if (info.get()!=null) break;
        Thread.sleep(500);
      }
      assertNotNull("Upload did not complete: " + status.get(), info.get());
      if (mode.equals("offline")) {
        scenario.onActivity(a -> {
          SubmitLogFragment fragment = (SubmitLogFragment)a.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
          SubmitLogViewModel model = new androidx.lifecycle.ViewModelProvider(fragment).get(SubmitLogViewModel.class);
          String sample = LogUploadResult.success("test server", "test-id", "test-key", 42).message;
          model.state.setValue(new SubmitLogViewModel.State(R.string.log_submit_uploaded_status, sample, false));
          String displayed = ((TextView)a.findViewById(R.id.log_submit_preview)).getText().toString();
          assertEquals(sample + "\n\n" + a.getString(R.string.log_submit_activity__thanks), displayed);
          assertTrue(a.findViewById(R.id.log_submit_share_link_button).isEnabled());
          assertFalse(displayed.contains("clipboard"));
          model.state.setValue(new SubmitLogViewModel.State(R.string.log_submit_no_internet, null, false));
        });
      }
      String previousInfo = info.get();
      scenario.onActivity(a -> {
        assertTrue(a.findViewById(R.id.log_submit_save_button).isEnabled());
        assertTrue(a.findViewById(R.id.log_submit_share_logs_button).isEnabled());
        assertEquals(mode.equals("online"), a.findViewById(R.id.log_submit_share_link_button).isEnabled());
      });
      scenario.recreate();
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(a -> {
        assertEquals(previousInfo, ((TextView)a.findViewById(R.id.log_submit_preview)).getText().toString());
        if (mode.equals("online")) assertTrue(previousInfo.endsWith(a.getString(R.string.log_submit_activity__thanks)));
        else assertEquals("", previousInfo);
      });
    }
  }
}
