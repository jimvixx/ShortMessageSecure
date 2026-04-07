/*
 * Copyright (C) 2014 Open Whisper Systems
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

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.app.ActivityOptionsCompat;

import android.util.DisplayMetrics;
import org.jimvixx.smsecure.logging.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class ConversationPopupActivity extends ConversationActivity {

  private static final String TAG = ConversationPopupActivity.class.getSimpleName();

  @Override
  protected void onPreCreate() {
    super.onPreCreate();
    overridePendingTransition(R.anim.slide_from_top, R.anim.slide_to_top);
  }

  @Override
  protected void onCreate(Bundle bundle, @NonNull MasterSecret masterSecret) {
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND);

    WindowManager.LayoutParams params = getWindow().getAttributes();
    params.alpha     = 1.0f;
    params.dimAmount = 0.1f;
    params.gravity   = Gravity.TOP;
    getWindow().setAttributes(params);

    DisplayMetrics metrics = new DisplayMetrics();
    getWindowManager().getDefaultDisplay().getMetrics(metrics);

    int width  = metrics.widthPixels;
    int height = metrics.heightPixels;

    if (height > width) getWindow().setLayout((int) (width * .85), (int) (height * .5));
    else                getWindow().setLayout((int) (width * .7), (int) (height * .75));

    super.onCreate(bundle, masterSecret);

    // Popup: disable opening recipient settings from title click
    if (titleView != null) {
      titleView.setOnClickListener(null);
    }

    // Popup: disable "up" affordance in the toolbar/actionbar
    disableUpNavigation();
  }

  private void disableUpNavigation() {
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setDisplayHomeAsUpEnabled(false);
      bar.setDisplayShowHomeEnabled(false);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (composeText != null) composeText.requestFocus();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (isFinishing()) overridePendingTransition(R.anim.slide_from_top, R.anim.slide_to_top);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    menu.clear();
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.conversation_popup, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();

    if (id == R.id.menu_expand) {
      saveDraft().addListener(new ListenableFuture.Listener<>() {
        @Override
        public void onSuccess(Long result) {
          ActivityOptionsCompat transition =
                  ActivityOptionsCompat.makeScaleUpAnimation(
                          getWindow().getDecorView(),
                          0,
                          0,
                          getWindow().getAttributes().width,
                          getWindow().getAttributes().height
                  );

          Intent intent = new Intent(ConversationPopupActivity.this, ConversationActivity.class);
          intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, getRecipients().getIds());
          intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, result);

          startActivity(intent, transition.toBundle());
          finish();
        }

        @Override
        public void onFailure(ExecutionException e) {
          Log.w(TAG, e);
        }
      });

      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void sendComplete(long threadId) {
    super.sendComplete(threadId);
    finish();
  }
}
