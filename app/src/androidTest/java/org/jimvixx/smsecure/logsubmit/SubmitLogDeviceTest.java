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

import android.content.Intent;
import android.widget.TextView;
import android.widget.EditText;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.jimvixx.smsecure.LogSubmitActivity;
import org.jimvixx.smsecure.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** UI-only instrumentation coverage; it never starts diagnostic collection or upload. */
@RunWith(AndroidJUnit4.class)
public class SubmitLogDeviceTest {
  @Test public void messageFormSurvivesRecreationWithoutNetwork() throws Exception {
    Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
            LogSubmitActivity.class)
            .putExtra(LogSubmitActivity.EXTRA_SKIP_LOG_UPLOAD, true);
    try (ActivityScenario<LogSubmitActivity> scenario = ActivityScenario.launch(intent)) {
      AtomicReference<String> previousInfo = new AtomicReference<>();
      scenario.onActivity(a -> {
        SubmitLogFragment fragment = (SubmitLogFragment)a.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);

        if (fragment == null) {
          throw new AssertionError("SubmitLogFragment not found");
        }

        SubmitLogViewModel model = new androidx.lifecycle.ViewModelProvider(fragment)
                .get(SubmitLogViewModel.class);
        String sample = LogUploadResult.success("test server", "test-id", "test-key", 42).message;
        model.logs = "test log";
        model.state.setValue(new SubmitLogViewModel.State(R.string.log_submit__log_uploaded_status, sample, false,
                true, false, false, 0));
        String displayed = ((TextView)a.findViewById(R.id.log_submit_preview)).getText().toString();
        previousInfo.set(displayed);
        assertEquals(sample + "\n\n" + a.getString(R.string.log_submit__thanks), displayed);
        assertTrue(a.findViewById(R.id.log_submit_share_link_button).isEnabled());
        assertEquals(View.VISIBLE, a.findViewById(R.id.log_submit_message_form).getVisibility());
        assertEquals(a.getString(R.string.log_submit__message_heading),
                ((TextView)a.findViewById(R.id.log_submit_message_heading)).getText().toString());
        assertFalse(a.findViewById(R.id.log_submit_message_button).isEnabled());
        ((EditText)a.findViewById(R.id.log_submit_message_input)).setText("Additional context");
        assertTrue(a.findViewById(R.id.log_submit_message_button).isEnabled());
        assertFalse(displayed.contains("clipboard"));
      });
      scenario.onActivity(a -> {
        assertTrue(a.findViewById(R.id.log_submit_save_button).isEnabled());
        assertTrue(a.findViewById(R.id.log_submit_share_logs_button).isEnabled());
      });
      scenario.recreate();
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(a -> {
        assertEquals(previousInfo.get(), ((TextView)a.findViewById(R.id.log_submit_preview)).getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.log_submit_message_form).getVisibility());
        assertEquals("Additional context",
                ((EditText)a.findViewById(R.id.log_submit_message_input)).getText().toString());
      });
    }
  }
}
