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

package org.fixtrading.silverflash.fixp.flow;

import static org.fixtrading.silverflash.fixp.SessionEventTopics.FromSessionEventType.SESSION_SUSPENDED;
import static org.fixtrading.silverflash.fixp.SessionEventTopics.SessionEventType.HEARTBEAT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.fixtrading.silverflash.Receiver;
import org.fixtrading.silverflash.fixp.SessionEventTopics;
import org.fixtrading.silverflash.fixp.messages.MessageEncoder;
import org.fixtrading.silverflash.fixp.messages.MessageType;
import org.fixtrading.silverflash.reactor.EventReactor;
import org.fixtrading.silverflash.reactor.Subscription;
import org.fixtrading.silverflash.reactor.TimerSchedule;
import org.fixtrading.silverflash.reactor.Topic;
import org.fixtrading.silverflash.transport.Transport;

/**
 * A flow for a one-way session. No application messages are sent.
 * 
 * @author Don Mendelson
 *
 */
public class NoneSender implements FlowSender {

  private final ByteBuffer heartbeatBuffer = ByteBuffer.allocateDirect(10).order(
      ByteOrder.nativeOrder());
  private final Receiver heartbeatEvent = new Receiver() {

    public void accept(ByteBuffer buffer) {
      try {
        sendHeartbeat();
      } catch (IOException e) {
        Topic terminatedTopic = SessionEventTopics.getTopic(sessionId, SESSION_SUSPENDED);
        reactor.post(terminatedTopic, buffer);
      }
    }

  };

  private final TimerSchedule heartbeatSchedule;
  private final Subscription heartbeatSubscription;
  private final AtomicBoolean isHeartbeatDue = new AtomicBoolean(true);
  private final MessageEncoder messageEncoder = new MessageEncoder();
  private final EventReactor<ByteBuffer> reactor;
  private final UUID sessionId;
  private final Transport transport;


  /**
   * Constructor
   * 
   * @param reactor an EventReactor
   * @param sessionId unique session ID
   * @param transport conveys messages
   * @param outboundKeepaliveInterval heartbeat interval
   */
  public NoneSender(EventReactor<ByteBuffer> reactor, final UUID sessionId,
      final Transport transport, int outboundKeepaliveInterval) {
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(transport);
    this.reactor = reactor;
    this.sessionId = sessionId;
    this.transport = transport;

    messageEncoder.attachForEncode(heartbeatBuffer, 0, MessageType.UNSEQUENCED_HEARTBEAT);

    if (outboundKeepaliveInterval != 0) {
      final Topic heartbeatTopic = SessionEventTopics.getTopic(sessionId, HEARTBEAT);
      heartbeatSubscription = reactor.subscribe(heartbeatTopic, heartbeatEvent);
      heartbeatSchedule =
          reactor.postAtInterval(heartbeatTopic, heartbeatBuffer, outboundKeepaliveInterval);
    } else {
      // No outbound heartbeats if multicast
      heartbeatSubscription = null;
      heartbeatSchedule = null;

    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.Sender#send(java.nio.ByteBuffer)
   */
  public long send(ByteBuffer message) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.fixp.flow.FlowSender#sendEndOfStream()
   */
  public void sendEndOfStream() throws IOException {
    // TODO Auto-generated method stub

  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.fixp.flow.FlowSender#sendHeartbeat()
   */
  public void sendHeartbeat() throws IOException {
    if (isHeartbeatDue.getAndSet(true)) {
      transport.write(heartbeatBuffer);
    }
  }

}
