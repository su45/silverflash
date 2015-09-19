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

package org.fixtrading.silverflash.fixp;

import static org.junit.Assert.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.fixtrading.silverflash.MessageConsumer;
import org.fixtrading.silverflash.Session;
import org.fixtrading.silverflash.auth.SimpleDirectory;
import org.fixtrading.silverflash.buffer.SingleBufferSupplier;
import org.fixtrading.silverflash.fixp.Engine;
import org.fixtrading.silverflash.fixp.FixpSession;
import org.fixtrading.silverflash.fixp.SessionId;
import org.fixtrading.silverflash.fixp.SessionReadyFuture;
import org.fixtrading.silverflash.fixp.SessionTerminatedFuture;
import org.fixtrading.silverflash.fixp.auth.SimpleAuthenticator;
import org.fixtrading.silverflash.fixp.messages.FlowType;
import org.fixtrading.silverflash.fixp.messages.MessageHeaderWithFrame;
import org.fixtrading.silverflash.reactor.ByteBufferDispatcher;
import org.fixtrading.silverflash.reactor.ByteBufferPayload;
import org.fixtrading.silverflash.reactor.EventReactor;
import org.fixtrading.silverflash.transport.TcpAcceptor;
import org.fixtrading.silverflash.transport.TcpConnectorTransport;
import org.fixtrading.silverflash.transport.Transport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TcpSessionTest {

  class TestReceiver implements MessageConsumer<UUID> {
    int bytesReceived = 0;
    private byte[] dst = new byte[16 * 1024];

    public int getBytesReceived() {
      return bytesReceived;
    }

    @Override
    public void accept(ByteBuffer buf, Session<UUID> session, long seqNo) {
      int bytesToReceive = buf.remaining();
      bytesReceived += bytesToReceive;
      buf.get(dst, 0, bytesToReceive);
    }
  }

  static final byte STREAM_ID = 99;

  private static final int templateId = 22;
  private static final int schemaVersion = 0;
  private static final int schemaId = 33;

  private Engine engine;
  private EventReactor<ByteBuffer> reactor2;
  private int messageCount = Byte.MAX_VALUE;
  private byte[][] messages;
  private int keepAliveInterval = 500;
  private String userCredentials = "User1";

  @Before
  public void setUp() throws Exception {
    SimpleDirectory directory = new SimpleDirectory();
    engine =
        Engine.builder().withAuthenticator(new SimpleAuthenticator().withDirectory(directory))
            .build();
    engine.open();

    reactor2 =
        EventReactor.builder().withDispatcher(new ByteBufferDispatcher())
            .withPayloadAllocator(new ByteBufferPayload(2048)).build();
    reactor2.open().get();

    directory.add(userCredentials);

    messages = new byte[messageCount][];
    for (int i = 0; i < messageCount; ++i) {
      messages[i] = new byte[i];
      Arrays.fill(messages[i], (byte) i);
    }
  }

  @After
  public void tearDown() throws Exception {
    engine.close();
    reactor2.close();
  }

  @Test
  public void sendIdempotent() throws Exception {
    TestReceiver serverReceiver = new TestReceiver();
    Function<Transport, FixpSession> clientAcceptor = new Function<Transport, FixpSession>() {

      public FixpSession apply(Transport serverTransport) {
        try {
          FixpSession serverSession =
              FixpSession
                  .builder()
                  .withReactor(engine.getReactor())
                  .withTransport(serverTransport)
                  .withBufferSupplier(
                      new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                          ByteOrder.nativeOrder()))).withMessageConsumer(serverReceiver)
                  .withOutboundFlow(FlowType.IDEMPOTENT)
                  .withOutboundKeepaliveInterval(keepAliveInterval).asServer().build();

          serverSession.open();

          return serverSession;
        } catch (Exception e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        return null;
      }
    };

    InetSocketAddress serverAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 7744);

    try (TcpAcceptor tcpAcceptor =
        new TcpAcceptor(engine.getIOReactor().getSelector(), serverAddress, clientAcceptor)) {
      tcpAcceptor.open().get();

      Transport clientTransport =
          new TcpConnectorTransport(engine.getIOReactor().getSelector(), serverAddress);
      TestReceiver clientReceiver = new TestReceiver();
      UUID sessionId = SessionId.generateUUID();

      FixpSession clientSession =
          FixpSession
              .builder()
              .withReactor(reactor2)
              .withTransport(clientTransport)
              .withBufferSupplier(
                  new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                      ByteOrder.nativeOrder()))).withMessageConsumer(clientReceiver)
              .withOutboundFlow(FlowType.IDEMPOTENT).withSessionId(sessionId)
              .withClientCredentials(userCredentials.getBytes())
              .withOutboundKeepaliveInterval(keepAliveInterval).build();

      SessionReadyFuture future = new SessionReadyFuture(sessionId, reactor2);
      clientSession.open();
      future.get(3000, TimeUnit.MILLISECONDS);

      ByteBuffer buf = ByteBuffer.allocate(8096).order(ByteOrder.nativeOrder());
      int bytesSent = 0;
      for (int i = 0; i < messageCount; ++i) {
        buf.clear();
        encodeApplicationMessageWithFrame(buf, messages[i]);
        bytesSent += buf.position();
        clientSession.send(buf);
      }

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {

      }
      assertEquals(bytesSent, serverReceiver.getBytesReceived());

      SessionTerminatedFuture future2 = new SessionTerminatedFuture(sessionId, reactor2);
      clientSession.close();
      future.get(1000, TimeUnit.MILLISECONDS);
    }
  }

  private void encodeApplicationMessageWithFrame(ByteBuffer buf, byte[] message) {
    MessageHeaderWithFrame.encode(buf, buf.position(), message.length, templateId, schemaId,
        schemaVersion, message.length + MessageHeaderWithFrame.getLength());
    buf.put(message);
  }

}
