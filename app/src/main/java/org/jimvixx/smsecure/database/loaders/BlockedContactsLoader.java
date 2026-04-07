package org.jimvixx.smsecure.database.loaders;

import android.content.Context;
import android.database.Cursor;

import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.util.AbstractCursorLoader;

public class BlockedContactsLoader extends AbstractCursorLoader {

  public BlockedContactsLoader(Context context) {
    super(context);
  }

  @Override
  public Cursor getCursor() {
    return DatabaseFactory.getRecipientPreferenceDatabase(getContext())
                          .getBlocked();
  }

}
