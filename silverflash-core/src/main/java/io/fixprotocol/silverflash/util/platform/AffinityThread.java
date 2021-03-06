/**
 *    Copyright 2015-2016 FIX Protocol Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.fixprotocol.silverflash.util.platform;

/**
 * A Thread that is optionally pinned to a core
 * 
 * @author Don Mendelson
 *
 */
class AffinityThread extends Thread {
  private final int coreToRunOn;
  private final boolean affinityEnabled;

  /**
   * Constructor
   * 
   * @param runnable function for thread to run
   */
  public AffinityThread(Runnable runnable) {
    this(runnable, 0, false, false);
  }

  /**
   * Constructor
   * 
   * @param runnable function for thread to run
   * @param coreToRunOn core to pin this thread to if affinity is enabled
   * @param affinityEnabled set {@code true} to pin to the core
   * @param isDaemon set {@code true} to make a daemon thread
   */
  public AffinityThread(Runnable runnable, int coreToRunOn, boolean affinityEnabled,
      boolean isDaemon) {
    super(runnable);
    this.coreToRunOn = coreToRunOn;
    this.affinityEnabled = affinityEnabled;
    setDaemon(isDaemon);
  }

  @Override
  public void run() {
    if (affinityEnabled) {
      CoreManager.setCurrentThreadAffinity(coreToRunOn);
    }
    super.run();
  }

}
