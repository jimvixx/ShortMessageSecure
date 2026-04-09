/*
 * Copyright (C) 2011 Whisper Systems
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

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.jimvixx.smsecure.crypto.IdentityKeyParcelable;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.storage.SMSecureIdentityKeyStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.IdentityDatabase;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.jobs.SmsDecryptJob;
import org.jimvixx.smsecure.protocol.KeyExchangeMessage;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.sms.IncomingIdentityUpdateMessage;
import org.jimvixx.smsecure.sms.IncomingKeyExchangeMessage;
import org.jimvixx.smsecure.sms.IncomingPreKeyBundleMessage;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.jimvixx.smsecure.util.Base64;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.state.IdentityKeyStore;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Activity for displaying sent/received session keys.
public class ReceiveKeyDialog extends AlertDialog {

  private static final String TAG = ReceiveKeyDialog.class.getSimpleName();

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private OnClickListener callback;

  public ReceiveKeyDialog(@NonNull Context context,
                          @NonNull MasterSecret masterSecret,
                          @NonNull MessageRecord messageRecord) {
    super(context);

    try {
      final IncomingKeyExchangeMessage message = getMessage(messageRecord);
      final IdentityKey identityKey = getIdentityKey(message);

      if (isTrusted(masterSecret, identityKey, messageRecord.getIndividualRecipient(), messageRecord.getSubscriptionId())) {
        setMessage(context.getString(R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_trusted_but));
      } else {
        setUntrustedText(messageRecord, identityKey);
      }

      setButton(BUTTON_POSITIVE,
              context.getString(R.string.receive_key_activity__complete),
              new AcceptListener(masterSecret, messageRecord, message, identityKey));

      setButton(BUTTON_NEGATIVE,
              context.getString(android.R.string.cancel),
              new CancelListener());

    } catch (InvalidKeyException | InvalidVersionException | InvalidMessageException |
             LegacyMessageException e) {
      throw new AssertionError(e);
    }
  }

  private static IncomingKeyExchangeMessage getMessage(@NonNull MessageRecord messageRecord)
          throws InvalidKeyException, InvalidVersionException, InvalidMessageException, LegacyMessageException {
    IncomingTextMessage message =
            new IncomingTextMessage(messageRecord.getIndividualRecipient().getNumber(),
                    messageRecord.getRecipientDeviceId(),
                    System.currentTimeMillis(),
                    messageRecord.getBody().getBody(),
                    messageRecord.getSubscriptionId());

    if (messageRecord.isBundleKeyExchange()) {
      return new IncomingPreKeyBundleMessage(message, message.getMessageBody());
    } else if (messageRecord.isIdentityUpdate()) {
      return new IncomingIdentityUpdateMessage(message, message.getMessageBody());
    } else {
      return new IncomingKeyExchangeMessage(message, message.getMessageBody());
    }
  }

  private static IdentityKey getIdentityKey(@NonNull IncomingKeyExchangeMessage message)
          throws InvalidKeyException, InvalidVersionException, InvalidMessageException, LegacyMessageException {
    try {
      if (message.isIdentityUpdate()) {
        return new IdentityKey(Base64.decodeWithoutPadding(message.getMessageBody()), 0);
      } else if (message.isPreKeyBundle()) {
        return new PreKeySignalMessage(Base64.decodeWithoutPadding(message.getMessageBody())).getIdentityKey();
      } else {
        return new KeyExchangeMessage(Base64.decodeWithoutPadding(message.getMessageBody())).getIdentityKey();
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void show() {
    super.show();

    // Avoid NPE: message view might not exist depending on dialog layout/theme.
    TextView messageView = findViewById(android.R.id.message);
    if (messageView != null) {
      messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }
  }

  @Override
  public void dismiss() {
    super.dismiss();
    executor.shutdownNow();
  }

  public void setCallback(@Nullable OnClickListener callback) {
    this.callback = callback;
  }

  private void setUntrustedText(@NonNull final MessageRecord messageRecord,
                                @NonNull final IdentityKey identityKey) {
    String introText = getContext().getString(
            R.string.ReceiveKeyActivity_the_signature_on_this_key_exchange_is_different);

    SpannableString spannableString =
            new SpannableString(introText + " " +
                    getContext().getString(R.string.ConfirmIdentityDialog_you_may_wish_to_verify_this_contact));

    spannableString.setSpan(new ClickableSpan() {
                              @Override
                              public void onClick(@NonNull View widget) {
                                Intent intent = new Intent(getContext(), VerifyIdentityActivity.class);
                                intent.putExtra("recipient", messageRecord.getIndividualRecipient().getRecipientId());
                                intent.putExtra("remote_identity", new IdentityKeyParcelable(identityKey));
                                getContext().startActivity(intent);
                              }
                            },
            introText.length() + 1,
            spannableString.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    setMessage(spannableString);
  }

  private boolean isTrusted(@NonNull MasterSecret masterSecret,
                            @NonNull IdentityKey identityKey,
                            @NonNull Recipient recipient,
                            int subscriptionId) {
    IdentityKeyStore identityKeyStore =
            new SMSecureIdentityKeyStore(getContext(), masterSecret, subscriptionId);

    return identityKeyStore.isTrustedIdentity(
            new SignalProtocolAddress(recipient.getNumber(), 1),
            identityKey,
            IdentityKeyStore.Direction.RECEIVING
    );
  }

  /// Override setButton to ensure our AcceptListener has access to this dialog instance.
  @Override
  public void setButton(int whichButton, CharSequence text, OnClickListener listener) {
    // If it's our AcceptListener, re-create it with a dialog ref.
    if (listener instanceof AcceptListener accept) {
      super.setButton(whichButton, text, accept.attach(this));
      return;
    }
    super.setButton(whichButton, text, listener);
  }

  private static final class AcceptListener implements OnClickListener {

    private final WeakReference<ReceiveKeyDialog> dialogRef;

    private final MasterSecret masterSecret;
    private final MessageRecord messageRecord;
    private final IncomingKeyExchangeMessage message;
    private final IdentityKey identityKey;

    private AcceptListener(@NonNull MasterSecret masterSecret,
                           @NonNull MessageRecord messageRecord,
                           @NonNull IncomingKeyExchangeMessage message,
                           @NonNull IdentityKey identityKey) {
      this.dialogRef = new WeakReference<>(null); // will be replaced in constructor below
      this.masterSecret = masterSecret;
      this.messageRecord = messageRecord;
      this.message = message;
      this.identityKey = identityKey;
    }

    private AcceptListener(@NonNull ReceiveKeyDialog dialog,
                           @NonNull MasterSecret masterSecret,
                           @NonNull MessageRecord messageRecord,
                           @NonNull IncomingKeyExchangeMessage message,
                           @NonNull IdentityKey identityKey) {
      this.dialogRef = new WeakReference<>(dialog);
      this.masterSecret = masterSecret;
      this.messageRecord = messageRecord;
      this.message = message;
      this.identityKey = identityKey;
    }

    // Helper: attach dialog after creation
    private AcceptListener attach(@NonNull ReceiveKeyDialog dialog) {
      return new AcceptListener(dialog, masterSecret, messageRecord, message, identityKey);
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int which) {
      ReceiveKeyDialog dialog = dialogRef.get();
      if (dialog == null) return;

      dialog.executor.execute(new AcceptRunner(dialog, masterSecret, messageRecord, message, identityKey));

      if (dialog.callback != null) dialog.callback.onClick(null, 0);
    }
  }

  private static final class AcceptRunner implements Runnable {

    private final WeakReference<ReceiveKeyDialog> dialogRef;

    private final MasterSecret masterSecret;
    private final MessageRecord messageRecord;
    private final IncomingKeyExchangeMessage message;
    private final IdentityKey identityKey;

    AcceptRunner(@NonNull ReceiveKeyDialog dialog,
                 @NonNull MasterSecret masterSecret,
                 @NonNull MessageRecord messageRecord,
                 @NonNull IncomingKeyExchangeMessage message,
                 @NonNull IdentityKey identityKey) {
      this.dialogRef = new WeakReference<>(dialog);
      this.masterSecret = masterSecret;
      this.messageRecord = messageRecord;
      this.message = message;
      this.identityKey = identityKey;
    }

    @Override
    public void run() {
      ReceiveKeyDialog dialog = dialogRef.get();
      if (dialog == null) return;

      Context context = dialog.getContext().getApplicationContext();
      IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(context);
      EncryptingSmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);

      identityDatabase.saveIdentity(masterSecret,
              messageRecord.getIndividualRecipient().getRecipientId(),
              identityKey);

      if (message.isIdentityUpdate()) {
        smsDatabase.markAsProcessedKeyExchange(messageRecord.getId());
      } else {
        ApplicationContext.getInstance(context)
                .getJobManager()
                .add(new SmsDecryptJob(context, messageRecord.getId(), true, false));
      }
    }
  }

  private class CancelListener implements OnClickListener {
    @Override
    public void onClick(DialogInterface dialog, int which) {
      if (callback != null) callback.onClick(null, 0);
    }
  }
}
