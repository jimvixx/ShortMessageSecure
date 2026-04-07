/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Jimvixx
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

import android.content.Context;
import android.text.Annotation;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.widget.MultiAutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatMultiAutoCompleteTextView;

import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.recipients.RecipientsFormatter;

/**
 * Minimal recipient editor with auto-complete support.
 * <p>
 * Kept features (actually needed by PushRecipientsPanel + RecipientsAdapter):
 * - Tokenization by ',' or ';'
 * - Token termination using the delimiter the user typed last (comma/semicolon)
 * - Annotation("number", ...) support for auto-complete inserted items
 * - Removal of annotations when the user edits previously inserted tokens
 * - Build Recipients from current raw input
 * - Populate the field from an existing Recipients list
 */
public class RecipientsEditor extends AppCompatMultiAutoCompleteTextView {

  private char lastSeparator = ',';

  public RecipientsEditor(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);

    RecipientsEditorTokenizer tokenizer = new RecipientsEditorTokenizer(this);
    setTokenizer(tokenizer);

    // Allow the focus to move forward when IME "Next" is pressed.
    setImeOptions(EditorInfo.IME_ACTION_NEXT);

    // Strip contact annotations when user edits the annotated region.
    addTextChangedListener(new AnnotationStrippingWatcher());
  }

  /**
   * Formats a single recipient and marks it with Annotation("number", ...).
   */
  public static @NonNull CharSequence contactToToken(@NonNull Recipient recipient) {
    String name = recipient.getName();
    String number = recipient.getNumber();

    SpannableString s = new SpannableString(RecipientsFormatter.formatNameAndNumber(name, number));
    int len = s.length();

    if (len > 0) {
      s.setSpan(new Annotation("number", number), 0, len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    return s;
  }

  @Override
  public boolean enoughToFilter() {
    if (!super.enoughToFilter()) return false;

    // Do not show auto-complete suggestions while editing an existing recipient.
    // This prevents duplicates (old token + new token).
    return getSelectionEnd() == length();
  }

  /**
   * Populates the field from an existing recipients list.
   */
  public void populate(@NonNull Recipients recipients) {
    SpannableStringBuilder sb = new SpannableStringBuilder();

    for (Recipient r : recipients.getRecipientsList()) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(contactToToken(r));
    }

    setText(sb);
    setSelection(sb.length());
  }

  /**
   * Removes annotations from the region that the user modifies.
   * This ensures edited tokens are treated as plain text.
   */
  private static final class AnnotationStrippingWatcher implements TextWatcher {

    private @Nullable Annotation[] affected;

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      if (s instanceof Spanned) {
        affected = ((Spanned) s).getSpans(start, start + count, Annotation.class);
      } else {
        affected = null;
      }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int after) {
      // no-op
    }

    @Override
    public void afterTextChanged(Editable s) {
      if (affected != null) {
        for (Annotation a : affected) s.removeSpan(a);
      }
      affected = null;
    }
  }

  /**
   * Tokenizer that separates recipients by ',' or ';' and terminates tokens consistently.
   */
  private final class RecipientsEditorTokenizer implements MultiAutoCompleteTextView.Tokenizer {

    private final @NonNull MultiAutoCompleteTextView list;

    RecipientsEditorTokenizer(@NonNull MultiAutoCompleteTextView list) {
      this.list = list;
    }

    @Override
    public int findTokenStart(CharSequence text, int cursor) {
      int i = cursor;

      while (i > 0) {
        char c = text.charAt(i - 1);
        if (c == ',' || c == ';') break;
        i--;
      }

      while (i < cursor && text.charAt(i) == ' ') i++;
      return i;
    }

    @Override
    public int findTokenEnd(CharSequence text, int cursor) {
      int i = cursor;
      int len = text.length();

      while (i < len) {
        char c = text.charAt(i);
        if (c == ',' || c == ';') return i;
        i++;
      }

      return len;
    }

    @Override
    public CharSequence terminateToken(CharSequence text) {
      int i = text.length();

      while (i > 0 && text.charAt(i - 1) == ' ') i--;

      if (i > 0) {
        char c = text.charAt(i - 1);
        if (c == ',' || c == ';') {
          // Token already terminated.
          return text;
        }
      }

      // Use the delimiter most recently typed in the field (fallback to previous value).
      lastSeparator = detectLastSeparatorOrDefault(lastSeparator);
      String separator = lastSeparator + " ";

      if (text instanceof Spanned) {
        SpannableString sp = new SpannableString(text + separator);
        TextUtils.copySpansFrom((Spanned) text, 0, text.length(), Object.class, sp, 0);
        return sp;
      }

      return text + separator;
    }

    private char detectLastSeparatorOrDefault(char def) {
      Editable e = list.getText();
      if (e == null) return def;

      for (int i = e.length() - 1; i >= 0; i--) {
        char c = e.charAt(i);
        if (c == ',' || c == ';') return c;
      }

      return def;
    }
  }
}