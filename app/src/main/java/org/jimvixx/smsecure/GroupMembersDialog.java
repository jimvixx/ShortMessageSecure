/*
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
import android.graphics.Rect;
import android.provider.ContactsContract;

import androidx.appcompat.app.AlertDialog;

import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.InvalidNumberException;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;

import java.util.LinkedList;
import java.util.List;

public class GroupMembersDialog {
  @SuppressWarnings("unused")
  private static final String TAG = GroupMembersDialog.class.getSimpleName();

  private final Recipients recipients;
  private final Context context;

  public GroupMembersDialog(Context context, Recipients recipients) {
    this.recipients = recipients;
    this.context = context;
  }

  public void display() {
    GroupMembers groupMembers = new GroupMembers(recipients);
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(R.string.ConversationActivity_group_members);
    builder.setIcon(R.drawable.ic_account_multiple);
    builder.setCancelable(true);
    builder.setItems(groupMembers.getRecipientStrings(), new GroupMembersOnClickListener(context, groupMembers));
    builder.setPositiveButton(android.R.string.ok, null);
    builder.show();
  }

  private static class GroupMembersOnClickListener implements DialogInterface.OnClickListener {
    private final GroupMembers groupMembers;
    private final Context context;

    public GroupMembersOnClickListener(Context context, GroupMembers members) {
      this.context = context;
      this.groupMembers = members;
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int item) {
      Recipient recipient = groupMembers.get(item);

      if (recipient.getContactUri() != null) {
        ContactsContract.QuickContact.showQuickContact(context, new Rect(0, 0, 0, 0),
                recipient.getContactUri(),
                ContactsContract.QuickContact.MODE_LARGE, null);
      } else {
        final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(ContactsContract.Intents.Insert.PHONE, recipient.getNumber());
        intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
        context.startActivity(intent);
      }
    }
  }

  /**
   * Wraps a List of Recipient (just like @class Recipients),
   * but with focus on the order of the Recipients.
   * So that the order of the RecipientStrings[] matches
   * the internal order.
   *
   * @author Christoph Haefner
   */
  private class GroupMembers {
    private final String TAG = GroupMembers.class.getSimpleName();

    private final LinkedList<Recipient> members = new LinkedList<>();

    public GroupMembers(Recipients recipients) {
      for (Recipient recipient : recipients.getRecipientsList()) {
        if (isLocalNumber(recipient)) {
          members.push(recipient);
        } else {
          members.add(recipient);
        }
      }
    }

    public String[] getRecipientStrings() {
      List<String> recipientStrings = new LinkedList<>();

      for (Recipient recipient : members) {
        if (isLocalNumber(recipient)) {
          recipientStrings.add(context.getString(R.string.GroupMembersDialog_me));
        } else {
          recipientStrings.add(((recipient.getName() != null) ? recipient.getName() : "") + "\n" + recipient.getNumber());
        }
      }

      return recipientStrings.toArray(new String[members.size()]);
    }

    public Recipient get(int index) {
      return members.get(index);
    }

    private boolean isLocalNumber(Recipient recipient) {
      try {
        String localNumber = SMSecurePreferences.getLocalNumber(context);
        String e164Number = Util.canonicalizeNumber(context, recipient.getNumber());

        return e164Number != null && e164Number.equals(localNumber);
      } catch (InvalidNumberException e) {
        Log.w(TAG, e);
        return false;
      }
    }
  }
}
