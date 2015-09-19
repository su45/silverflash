/**
 *    Copyright 2015 FIX Protocol Ltd
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

package org.fixtrading.silverflash;

import java.io.IOException;

/**
 * An instance of a session layer to exchange messages between peers (OSI layer 5)
 * 
 * @author Don Mendelson
 * 
 * @param T identifier type
 */
public interface Session<T> extends Sender, AutoCloseable {

  /**
   * @return a unique session ID
   */
  T getSessionId();

  /**
   * Initialize this Session. Open a Transport and acquire any other needed resources.
   * 
   * @throws IOException if an IO error occurs
   */
  void open() throws IOException;

}
