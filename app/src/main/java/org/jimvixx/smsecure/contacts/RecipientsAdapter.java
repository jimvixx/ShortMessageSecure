/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jimvixx.smsecure.contacts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.view.View;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.recipients.RecipientsFormatter;

/**
 * This adapter is used to filter contacts on both name and number.
 */
public class RecipientsAdapter extends ResourceCursorAdapter {

  public static final int CONTACT_ID_INDEX = 1;
  public static final int TYPE_INDEX       = 2;
  public static final int NUMBER_INDEX     = 3;
  public static final int LABEL_INDEX      = 4;
  public static final int NAME_INDEX       = 5;

  private final Context mContext;
  private final ContentResolver mContentResolver;
  private final ContactAccessor mContactAccessor;

  public RecipientsAdapter(Context context) {
    // Use the non-deprecated constructor with flags.
    super(context, R.layout.recipient_filter_item, null, 0);
    mContext = context;
    mContentResolver = context.getContentResolver();
    mContactAccessor = ContactAccessor.getInstance();
  }

  @Override
  public final CharSequence convertToString(Cursor cursor) {
    String name   = cursor.getString(NAME_INDEX);
    int type      = cursor.getInt(TYPE_INDEX);
    String number = cursor.getString(NUMBER_INDEX);

    if (number == null) number = "";
    number = number.trim();

    if (number.isEmpty()) {
      return number;
    }

    String label = cursor.getString(LABEL_INDEX);
    CharSequence displayLabel = mContactAccessor.phoneTypeToString(mContext, type, label);

    if (name == null) {
      name = "";
    } else {
      // Names with commas confuse the recipient editor because commas are used as separators.
      // Remove commas to prevent edge cases with spans and improve UX.
      name = name.replace(", ", " ")
              .replace(",", " ");
    }

    String nameAndNumber = RecipientsFormatter.formatNameAndNumber(name, number);

    SpannableString out = new SpannableString(nameAndNumber);
    int len = out.length();

    if (!TextUtils.isEmpty(name)) {
      out.setSpan(new Annotation("name", name), 0, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    } else {
      out.setSpan(new Annotation("name", number), 0, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    String personId = cursor.getString(CONTACT_ID_INDEX);
    if (personId == null) personId = "";

    out.setSpan(new Annotation("person_id", personId), 0, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    out.setSpan(new Annotation("label", displayLabel != null ? displayLabel.toString() : ""), 0, len,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    out.setSpan(new Annotation("number", number), 0, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return out;
  }

  @Override
  public final void bindView(View view, Context context, Cursor cursor) {
    TextView nameView = view.findViewById(R.id.name);
    String name = cursor.getString(NAME_INDEX);
    nameView.setText(name != null ? name : "");

    TextView labelView = view.findViewById(R.id.label);
    int type = cursor.getInt(TYPE_INDEX);
    CharSequence label = mContactAccessor.phoneTypeToString(mContext, type, cursor.getString(LABEL_INDEX));
    labelView.setText(label != null ? label : "");

    TextView numberView = view.findViewById(R.id.number);
    String number = cursor.getString(NUMBER_INDEX);
    if (number == null) number = "";

    // Requires a string resource with a placeholder to avoid concatenation.
    // Example value: "(%1$s)"
    numberView.setText(context.getString(R.string.recipient_number_parens, number));
  }

  @Override
  public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
    return mContactAccessor.getCursorForRecipientFilter(constraint, mContentResolver);
  }

  /**
   * Returns true if all the characters are meaningful as digits in a phone number:
   * letters, digits, and a few punctuation marks.
   */
  public static boolean usefulAsDigits(CharSequence cons) {
    int len = cons.length();

    for (int i = 0; i < len; i++) {
      char c = cons.charAt(i);

      if (c >= '0' && c <= '9') continue;

      if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '+'
              || c == '#' || c == '*') {
        continue;
      }

      if (c >= 'A' && c <= 'Z') continue;
      if (c >= 'a' && c <= 'z') continue;

      return false;
    }

    return true;
  }
}