/*
 * Copyright (C) 2013 Open Whisper Systems
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

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.logsubmit.SubmitLogFragment;

public class LogSubmitActivity extends BaseActionBarActivity
        implements SubmitLogFragment.OnLogSubmittedListener {

  @Override
  protected void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    setContentView(R.layout.log_submit_activity);

    initializeToolbar();

    if (icicle == null) {
      getSupportFragmentManager()
              .beginTransaction()
              .replace(R.id.fragment_container, SubmitLogFragment.newInstance())
              .commit();
    }
  }

  private void initializeToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar == null) return;

    setSupportActionBar(toolbar);

    ActionBar ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowHomeEnabled(true);
    }
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onFailure() {
    Toast.makeText(getApplicationContext(),
            R.string.log_submit_activity__log_fetch_failed,
            Toast.LENGTH_LONG).show();
  }

  @Override
  public void onSuccess() {
    Toast.makeText(getApplicationContext(),
            R.string.log_submit_activity__thanks,
            Toast.LENGTH_LONG).show();
  }
}
