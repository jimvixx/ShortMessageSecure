/*
 * Copyright (C) 2015 Open Whisper Systems
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

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.Rfc5724Uri;

public class SmsSendtoActivity extends Activity {

  private static final String TAG = SmsSendtoActivity.class.getSimpleName();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    startActivity(getNextIntent(getIntent()));
    finish();
    super.onCreate(savedInstanceState);
  }

  private Intent getNextIntent(Intent original) {
    DestinationAndBody destination;

    if (Intent.ACTION_SENDTO.equals(original.getAction())) {
      destination = getDestinationForSendTo(original);
    } else if (original.getData() != null &&
            "content".equals(original.getData().getScheme())) {
      destination = getDestinationForSyncAdapter(original);
    } else {
      destination = getDestinationForView(original);
    }

    Recipients recipients =
            RecipientFactory.getRecipientsFromString(this, destination.getDestination(), true);

    long threadId =
            DatabaseFactory.getThreadDatabase(this)
                    .getThreadIdIfExistsFor(recipients);

    final Intent nextIntent;

    if (recipients.isEmpty()) {
      nextIntent = new Intent(this, NewConversationActivity.class);
      nextIntent.putExtra(ConversationActivity.TEXT_EXTRA, destination.getBody());
      Toast.makeText(this,
              R.string.ConversationActivity_specify_recipient,
              Toast.LENGTH_LONG).show();
    } else {
      nextIntent = new Intent(this, ConversationActivity.class);
      nextIntent.putExtra(ConversationActivity.TEXT_EXTRA, destination.getBody());
      nextIntent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
      nextIntent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.getIds());
    }

    return nextIntent;
  }

  private @NonNull DestinationAndBody getDestinationForSendTo(Intent intent) {
    return new DestinationAndBody(
            intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart()
                    : "",
            intent.getStringExtra("sms_body"));
  }

  private @NonNull DestinationAndBody getDestinationForView(Intent intent) {
    if (intent.getData() == null) {
      return new DestinationAndBody("", "");
    }

    String raw = intent.getData().toString();
    if (TextUtils.isEmpty(raw)) {
      return new DestinationAndBody("", "");
    }

    Rfc5724Uri smsUri = new Rfc5724Uri(raw);

    String body = smsUri.getQueryParams().get("body");

    return new DestinationAndBody(
            smsUri.getPath(),
            body != null ? body : "");
  }

  private @NonNull DestinationAndBody getDestinationForSyncAdapter(Intent intent) {
    if (intent.getData() == null) {
      return new DestinationAndBody("", "");
    }

    try (Cursor cursor = getContentResolver().query(
            intent.getData(),
            null,
            null,
            null,
            null)) {

      if (cursor != null && cursor.moveToNext()) {
        return new DestinationAndBody(
                cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                ContactsContract.RawContacts.Data.DATA1)),
                "");
      }

      return new DestinationAndBody("", "");
    }
  }

  private static class DestinationAndBody {
    private final String destination;
    private final String body;

    private DestinationAndBody(String destination, String body) {
      this.destination = destination;
      this.body = body;
    }

    public String getDestination() {
      return destination;
    }

    public String getBody() {
      return body;
    }
  }
}
