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

package org.jimvixx.smsecure.migration.silence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SilencePreflightResult {
  public enum Status { STRUCTURALLY_VALID, REJECTED }
  private final SilenceBackupInfo info;
  private final List<String> findings;
  private SilencePreflightResult(SilenceBackupInfo info, List<String> findings) {
    this.info = info;
    this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
  }
  public static SilencePreflightResult valid(SilenceBackupInfo info) {
    if (info == null) throw new IllegalArgumentException("Missing backup metadata");
    List<String> findings = new ArrayList<>();
    findings.add("Crypto integrity and decryptability have not been verified.");
    findings.add("The export has no version manifest; schema 30 matches the reference format.");
    if (info.getMmsCount() > 0) findings.add("SMSecure does not support MMS or attachments.");
    return new SilencePreflightResult(info, findings);
  }
  public static SilencePreflightResult rejected() {
    return new SilencePreflightResult(null, Collections.singletonList("Backup validation failed."));
  }
  public Status getStatus() { return info == null ? Status.REJECTED : Status.STRUCTURALLY_VALID; }
  public SilenceBackupInfo getInfo() { return info; }
  public List<String> getFindings() { return findings; }
  /** This slice never authorizes an apply operation. */
  public boolean isReadyToImport() { return false; }
}
