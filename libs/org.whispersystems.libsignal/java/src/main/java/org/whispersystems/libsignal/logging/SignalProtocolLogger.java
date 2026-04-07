/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.libsignal.logging;

public interface SignalProtocolLogger {

  int VERBOSE = 2;
  int DEBUG   = 3;
  int INFO    = 4;
  int WARN    = 5;
  int ERROR   = 6;
  int ASSERT  = 7;

  void log(int priority, String tag, String message);
}
