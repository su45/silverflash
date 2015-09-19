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

package org.fixtrading.silverflash.examples;

import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;
import org.fixtrading.silverflash.ExceptionConsumer;
import org.fixtrading.silverflash.MessageConsumer;
import org.fixtrading.silverflash.Session;
import org.fixtrading.silverflash.buffer.SingleBufferSupplier;
import org.fixtrading.silverflash.examples.messages.AcceptedDecoder;
import org.fixtrading.silverflash.examples.messages.CrossType;
import org.fixtrading.silverflash.examples.messages.CustomerType;
import org.fixtrading.silverflash.examples.messages.Display;
import org.fixtrading.silverflash.examples.messages.EnterOrderEncoder;
import org.fixtrading.silverflash.examples.messages.IntermarketSweepEligibility;
import org.fixtrading.silverflash.examples.messages.OrderCapacity;
import org.fixtrading.silverflash.examples.messages.Side;
import org.fixtrading.silverflash.fixp.Engine;
import org.fixtrading.silverflash.fixp.FixpSharedTransportAdaptor;
import org.fixtrading.silverflash.fixp.SessionReadyFuture;
import org.fixtrading.silverflash.fixp.SessionTerminatedFuture;
import org.fixtrading.silverflash.fixp.messages.FlowType;
import org.fixtrading.silverflash.fixp.messages.MessageHeaderWithFrame;
import org.fixtrading.silverflash.transport.SharedMemoryTransport;
import org.fixtrading.silverflash.transport.TcpConnectorTransport;
import org.fixtrading.silverflash.transport.Transport;

import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

/**
 * Test order server
 * <p>
 * FixpSession layer: FIXP
 * <p>
 * Presentation layer: SBE
 * <p>
 * Command line:
 * {@code java -cp session-perftest-0.0.1-SNAPSHOT-jar-with-dependencies.jar org.fixtrading.silverflash.examples.BuySide <properties-file> }
 *
 * @author Don Mendelson
 * 
 */
public class BuySide implements Runnable {

  private class ClientRunner implements Runnable {

    private final long batchPauseMillis;
    private final int batchSize;
    private final Histogram burstHistogram;
    private final Session<UUID> client;
    private final byte[] clOrdId = new byte[14];
    private final ByteBuffer clOrdIdBuffer;
    private final int orders;
    private final int ordersPerPacket;
    private final Histogram rttHistogram;
    private SessionTerminatedFuture terminatedFuture;

    public ClientRunner(Session<UUID> client, Histogram rttHistogram, int orders, int batchSize,
        int ordersPerPacket, long batchPauseMillis) {
      this.client = client;
      this.rttHistogram = rttHistogram;

      final long highestTrackableValue = TimeUnit.MINUTES.toNanos(1);
      final int numberOfSignificantValueDigits = 3;
      burstHistogram = new Histogram(highestTrackableValue, numberOfSignificantValueDigits);

      this.orders = orders;
      this.batchSize = batchSize;
      this.ordersPerPacket = ordersPerPacket;
      this.batchPauseMillis = batchPauseMillis;
      System.arraycopy("client00000000".getBytes(), 0, clOrdId, 0, 14);
      clOrdIdBuffer = ByteBuffer.wrap(clOrdId);
    }

    /**
     * Attempts to establish a session, and when done successfully, performs injection on its own
     * thread.
     * 
     * @return a future to handle session termination asynchronously
     * @throws Exception
     */
    public SessionTerminatedFuture connect() {
      terminatedFuture = new SessionTerminatedFuture(client.getSessionId(), engine.getReactor());

      try {
        CompletableFuture<ByteBuffer> readyFuture =
            new SessionReadyFuture(client.getSessionId(), engine.getReactor());
        client.open();
        readyFuture.get(1000000, TimeUnit.MILLISECONDS);
        System.out.println("Connected; session ID=" + client.getSessionId());
        executor.execute(this);
      } catch (Exception e) {
        System.out.println("Failed to connect; session ID=" + client.getSessionId() + "; "
            + e.getMessage());
        terminatedFuture.completeExceptionally(e);
      }
      return terminatedFuture;
    }

    /**
     * @return the burstHistogram
     */
    public Histogram getBurstHistogram() {
      return burstHistogram;
    }

    public void inject(Session<UUID> client, Histogram rttHistogram, int orders, int batchSize,
        int ordersPerPacket, long batchPauseMillis) throws IOException {
      int iterations = orders / batchSize;
      int iterationsToIgnore = (int) Math.ceil(10000.0 / batchSize);
      int packetsPerBatch = (int) Math.ceil((double) batchSize / ordersPerPacket);

      System.out.format(
          "Batch size=%d orders per packet=%d iterations=%d iterations to ignore=%d\n", batchSize,
          ordersPerPacket, iterations, iterationsToIgnore);

      rttHistogram.reset();
      burstHistogram.reset();
      int orderNumber = 0;

      final ByteBuffer[] toServerByteBuffer = new ByteBuffer[packetsPerBatch];
      for (int i = 0; i < packetsPerBatch; i++) {
        toServerByteBuffer[i] = ByteBuffer.allocateDirect(1420).order(ByteOrder.nativeOrder());
      }
      final MutableDirectBuffer[] toServerBuffer = new MutableDirectBuffer[packetsPerBatch];
      for (int i = 0; i < packetsPerBatch; i++) {
        toServerBuffer[i] = new UnsafeBuffer(toServerByteBuffer[i]);
      }

      for (int j = 0; j < iterations; j++) {

        for (int i = 0; i < packetsPerBatch; i++) {
          if (ordersPerPacket == 1) {
            toServerByteBuffer[i].clear();
            encodeOrder(toServerBuffer[i], toServerByteBuffer[i], (j > iterationsToIgnore),
                ++orderNumber);
          } else {
            for (int h = 0; h < ordersPerPacket; h++) {
              toServerByteBuffer[i].clear();
              encodeOrder(toServerBuffer[i], toServerByteBuffer[i], (j > iterationsToIgnore),
                  ++orderNumber);
            }
          }
        }

        long start = System.nanoTime();

        if (ordersPerPacket == 1) {
          for (int i = 0; i < packetsPerBatch; i++) {
            long seqNo = client.send(toServerByteBuffer[i]);
          }
        } else {
          client.send(toServerByteBuffer);
        }

        long end = System.nanoTime();
        if (j > iterationsToIgnore) {
          burstHistogram.recordValue(end - start);
        }

        try {

          Thread.sleep(batchPauseMillis);
        } catch (InterruptedException e) {
        }

      }

      try {

        Thread.sleep(100 * batchPauseMillis);
      } catch (InterruptedException e) {
      }

    }

    /**
		 * 
		 */
    public void report() {
      System.out.format("Client session ID %s\nRTT microseconds\n", client.getSessionId());
      printStats(rttHistogram);
      System.out.format("Burst injection microseconds - burst size %d\n", batchSize);
      printStats(getBurstHistogram());
    }

    @Override
    public void run() {
      try {
        inject(client, rttHistogram, orders, batchSize, ordersPerPacket, batchPauseMillis);
      } catch (Exception e) {
        terminatedFuture.completeExceptionally(e);
      } finally {
        try {
          client.close();
        } catch (Exception e) {
          terminatedFuture.completeExceptionally(e);
        }
        System.out.println("Buy side session closed");
      }
    }

    private void encodeOrder(MutableDirectBuffer directBuffer, ByteBuffer byteBuffer,
        boolean shouldTimestamp, int orderNumber) {
      int bufferOffset = byteBuffer.position();
      MessageHeaderWithFrame.encode(byteBuffer, bufferOffset, order.sbeBlockLength(),
          order.sbeTemplateId(), order.sbeSchemaVersion(), order.sbeSchemaVersion(),
          MessageHeaderWithFrame.getLength() + order.sbeBlockLength());

      bufferOffset += MessageHeaderWithFrame.getLength();

      order.wrap(directBuffer, bufferOffset);

      clOrdIdBuffer.putInt(6, orderNumber);
      order.putClOrdId(clOrdId, 0);
      order.side(Side.Sell);
      order.orderQty(1L);
      order.putSymbol(symbol, 0);
      order.price().mantissa(10000000);
      order.expireTime(1000L);
      order.putClientID(clientId, 0);
      order.display(Display.AnonymousPrice);
      order.orderCapacity(OrderCapacity.Agency);
      order.intermarketSweepEligibility(IntermarketSweepEligibility.Eligible);
      order.minimumQuantity(1L);
      order.crossType(CrossType.NoCross);
      order.customerType(CustomerType.Retail);
      if (shouldTimestamp) {
        order.transactTime(System.nanoTime());
      } else {
        order.transactTime(0);
      }
      byteBuffer.position(bufferOffset + order.sbeBlockLength());
    }
  }

  class ClientListener implements MessageConsumer<UUID> {

    class AcceptStruct {
      byte[] clientId = new byte[4];
      byte[] clOrdId = new byte[14];
      long orderId;
      byte[] symbol = new byte[8];
    }

    private final AcceptedDecoder accept = new AcceptedDecoder();
    private final AcceptStruct acceptStruct = new AcceptStruct();
    private final DirectBuffer directBuffer = new UnsafeBuffer(ByteBuffer.allocate(0));
    private final MessageHeaderWithFrame messageHeaderIn = new MessageHeaderWithFrame();
    private final Histogram rttHistogram;

    public ClientListener(Histogram rttHistogram) {
      this.rttHistogram = rttHistogram;
    }

    public void accept(ByteBuffer buffer, Session<UUID> session, long seqNo) {

      messageHeaderIn.attachForDecode(buffer, buffer.position());

      final int templateId = messageHeaderIn.getTemplateId();
      switch (templateId) {
        case AcceptedDecoder.TEMPLATE_ID:
          decodeAccepted(buffer, acceptStruct);
          break;
        default:
          System.err.format("BuySide Receiver: Unknown template %s\n", messageHeaderIn.toString());
      }
    }

    private void decodeAccepted(ByteBuffer buffer, AcceptStruct acceptStruct) {
      try {
        directBuffer.wrap(buffer);
        accept.wrap(directBuffer, buffer.position() + MessageHeaderWithFrame.getLength(),
            messageHeaderIn.getBlockLength(), messageHeaderIn.getSchemaVersion());
        // long transactTime = accept.transactTime();
        // accept.getClOrdId(acceptStruct.clOrdId, 0);
        // accept.side();
        // accept.orderQty();
        // accept.getSymbol(acceptStruct.symbol, 0);
        // accept.price();
        // accept.expireTime();
        // accept.getClientID(acceptStruct.clientId, 0);
        // accept.display();
        // acceptStruct.orderId = accept.orderId();
        // accept.orderCapacity();
        // accept.intermarketSweepEligibility();
        // accept.minimumQuantity();
        // accept.crossType();
        // accept.ordStatus();
        // accept.bBOWeightIndicator();
        long orderEntryTime = accept.orderEntryTime();
        if (orderEntryTime != 0) {
          long now = System.nanoTime();
          rttHistogram.recordValue(now - orderEntryTime);
        }
      } catch (IllegalArgumentException e) {
        System.err.format("Decode error; %s buffer %s\n", e.getMessage(), buffer.toString());
      }
    }

  }

  /*
   * Configuration keys
   */
  public static final String CLIENT_FLOW_RECOVERABLE_KEY = "recoverable";
  public static final String CLIENT_FLOW_SEQUENCED_KEY = "sequenced";
  public static final String CLIENT_KEEPALIVE_INTERVAL_KEY = "heartbeatInterval";
  public static final String CSET_MAX_CORE = "maxCore";
  public static final String CSET_MIN_CORE = "minCore";
  public static final String INJECT_BATCH_PAUSE_MILLIS = "batchPause";
  public static final String INJECT_BATCH_SIZE = "batchSize";
  public static final String INJECT_ORDERS_PER_PACKET = "ordersPerPacket";
  public static final String INJECT_ORDERS_TO_SEND = "orders";
  public static final String LOCAL_HOST_KEY = "localhost";
  public static final String LOCAL_PORT_KEY = "localport";
  public static final String MULTIPLEXED_KEY = "multiplexed";
  public static final String NUMBER_OF_CLIENTS = "clients";
  public static final String PROTOCOL_KEY = "protocol";
  public static final String PROTOCOL_SHARED_MEMORY = "sharedmemory";
  public static final String PROTOCOL_SSL = "ssl";
  public static final String PROTOCOL_TCP = "tcp";
  public static final String PROTOCOL_UDP = "udp";
  public static final String REMOTE_HOST_KEY = "remotehost";
  public static final String REMOTE_PORT_KEY = "remoteport";
  public static final String REMOTE_RECOVERY_HOST_KEY = "remoteRecoveryHost";
  public static final String REMOTE_RECOVERY_PORT_KEY = "remoteRecoveryPort";
  public static final String SERVER_RECOVERY_INBAND = "inBand";
  public static final String SERVER_RECOVERY_KEY = "serverRecovery";
  public static final String SERVER_RECOVERY_OUTOFBAND = "outOfBand";

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: java org.fixtrading.silverflash.examples.BuySide <conf-filename>");
      System.exit(1);
    }

    Properties props = loadProperties(args[0]);
    final BuySide buySide = new BuySide(props);
    buySide.init();
    buySide.run();
  }

  private static Properties getDefaultProperties() {
    Properties defaults = new Properties();
    defaults.setProperty(CLIENT_FLOW_SEQUENCED_KEY, "true");
    defaults.setProperty(CLIENT_FLOW_RECOVERABLE_KEY, "false");
    defaults.setProperty(INJECT_ORDERS_TO_SEND, Integer.toString(500));
    defaults.setProperty(INJECT_ORDERS_PER_PACKET, Integer.toString(10));
    defaults.setProperty(PROTOCOL_KEY, PROTOCOL_TCP);
    defaults.setProperty(MULTIPLEXED_KEY, "false");
    defaults.setProperty(LOCAL_HOST_KEY, "localhost");
    defaults.setProperty(LOCAL_PORT_KEY, "6868");
    defaults.setProperty(REMOTE_HOST_KEY, "localhost");
    defaults.setProperty(REMOTE_PORT_KEY, "6869");
    defaults.setProperty(REMOTE_RECOVERY_HOST_KEY, "localhost");
    defaults.setProperty(REMOTE_RECOVERY_PORT_KEY, "6867");
    defaults.setProperty(CLIENT_KEEPALIVE_INTERVAL_KEY, "1000");
    defaults.setProperty(INJECT_BATCH_SIZE, "100");
    defaults.setProperty(INJECT_BATCH_PAUSE_MILLIS, "200");
    defaults.setProperty(NUMBER_OF_CLIENTS, "1");
    defaults.setProperty(SERVER_RECOVERY_KEY, SERVER_RECOVERY_INBAND);
    defaults.setProperty(CSET_MIN_CORE, "0");
    defaults.setProperty(CSET_MAX_CORE, "7");
    return defaults;
  }

  private static Properties loadProperties(String fileName) throws IOException {
    Properties defaults = getDefaultProperties();
    Properties props = new Properties(defaults);

    try (final FileReader reader = new FileReader(fileName)) {
      props.load(reader);
    } catch (IOException e) {
      System.err.format("Failed to read properties from file %s\n", fileName);
      throw e;
    }
    return props;
  }

  private static void printStats(Histogram data) {
    long totalCount = data.getTotalCount();
    System.out.println("Total count: " + totalCount);
    if (totalCount > 0) {
      System.out.println("MIN\tMAX\tMEAN\t30%\t50%\t90%\t95%\t99%\t99.99%\tSTDDEV");
      System.out.format("%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\n",
          TimeUnit.NANOSECONDS.toMicros(data.getMinValue()),
          TimeUnit.NANOSECONDS.toMicros(data.getMaxValue()),
          TimeUnit.NANOSECONDS.toMicros((long) data.getMean()),
          TimeUnit.NANOSECONDS.toMicros(data.getValueAtPercentile(30.0)),
          TimeUnit.NANOSECONDS.toMicros(data.getValueAtPercentile(50.0)),
          TimeUnit.NANOSECONDS.toMicros(data.getValueAtPercentile(90.0)),
          TimeUnit.NANOSECONDS.toMicros(data.getValueAtPercentile(95.0)),
          TimeUnit.NANOSECONDS.toMicros(data.getValueAtPercentile(99.0)),
          TimeUnit.NANOSECONDS.toMicros(data.getValueAtPercentile(99.99)),
          TimeUnit.NANOSECONDS.toMicros((long) data.getStdDeviation()));
    }
  }

  private final SessionConfigurationService clientConfig = new SessionConfigurationService() {

    public byte[] getCredentials() {
      return "TestUser".getBytes();
    }

    // Hearbeat interval in millis
    public int getKeepaliveInterval() {
      return Integer.parseInt(props.getProperty(CLIENT_KEEPALIVE_INTERVAL_KEY));
    }

    @Override
    public boolean isOutboundFlowRecoverable() {
      return Boolean.parseBoolean(props.getProperty(CLIENT_FLOW_RECOVERABLE_KEY));
    }

    public boolean isOutboundFlowSequenced() {
      return Boolean.parseBoolean(props.getProperty(CLIENT_FLOW_SEQUENCED_KEY));
    }


    public boolean isTransportMultiplexed() {
      return Boolean.parseBoolean(props.getProperty(MULTIPLEXED_KEY));
    }

  };

  private final byte[] clientId = "0999".getBytes();
  private Engine engine;

  private ExceptionConsumer exceptionConsumer = ex -> {
    System.err.println(ex);
  };

  private ExecutorService executor;
  private int numberOfClients;
  private final EnterOrderEncoder order = new EnterOrderEncoder();
  private int ordersToSend;
  private final Properties props;
  private ClientRunner[] runners;
  private Transport sharedTransport = null;
  private final byte[] symbol = "ESH8    ".getBytes();

  /**
   * Create an injector with default properties
   */
  public BuySide() {
    this.props = getDefaultProperties();
  }

  /**
   * Create an injector
   * 
   * @param props configuration
   */
  public BuySide(Properties props) {
    this.props = getConfigurationWithDefaults(props);
    ordersToSend = Integer.parseInt(this.props.getProperty(INJECT_ORDERS_TO_SEND));
  }

  public Session<UUID> createSession(int sessionIndex, FixpSessionFactory fixpSessionFactory,
      Histogram rttHistogram) throws Exception {
    String protocol = props.getProperty(PROTOCOL_KEY);
    String serverRecovery = props.getProperty(SERVER_RECOVERY_KEY);

    boolean isSequenced = clientConfig.isOutboundFlowSequenced();
    boolean isRecoverable = clientConfig.isOutboundFlowRecoverable();
    FlowType outboundFlow =
        isSequenced ? (isRecoverable ? FlowType.RECOVERABLE : FlowType.IDEMPOTENT)
            : FlowType.UNSEQUENCED;
    boolean isMultiplexed = clientConfig.isTransportMultiplexed();

    String remotehost = props.getProperty(REMOTE_HOST_KEY);
    int remoteport = Integer.parseInt(props.getProperty(REMOTE_PORT_KEY));
    SocketAddress remoteAddress = null;
    if (remotehost != null) {
      remoteAddress = new InetSocketAddress(remotehost, remoteport);
    }

    ClientListener clientListener = new ClientListener(rttHistogram);

    Transport transport;
    transport = createTransport(protocol, isMultiplexed, remoteAddress);

    String user = "client" + sessionIndex;
    byte[] credentials = user.getBytes();
    return fixpSessionFactory.createClientSession(credentials, transport, new SingleBufferSupplier(
        ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.nativeOrder())), clientListener,
        outboundFlow);
  }

  /**
   * @param props
   * @return
   */
  public Properties getConfigurationWithDefaults(Properties props) {
    Properties defaults = getDefaultProperties();
    Properties props2 = new Properties(defaults);
    props2.putAll(props);
    return props2;
  }

  /**
   * @return the ordersToSend
   */
  public int getOrdersToSend() {
    return ordersToSend;
  }

  public void init() throws Exception {

    final Object batchSizeString = props.getProperty(INJECT_BATCH_SIZE);
    final int batchSize = Integer.parseInt((String) batchSizeString);
    final int ordersPerPacket = Integer.parseInt(props.getProperty(INJECT_ORDERS_PER_PACKET));
    final long batchPauseMillis = Long.parseLong(props.getProperty(INJECT_BATCH_PAUSE_MILLIS));
    numberOfClients = Integer.parseInt(props.getProperty(NUMBER_OF_CLIENTS));
    final long highestTrackableValue = TimeUnit.MINUTES.toNanos(1);
    final int numberOfSignificantValueDigits = 3;

    int minCore = Integer.parseInt(props.getProperty(CSET_MIN_CORE));
    int maxCore = Integer.parseInt(props.getProperty(CSET_MAX_CORE));

    engine = Engine.builder().withCoreRange(minCore, maxCore).build();
    // engine.getReactor().setTrace(true, "client");
    engine.open();
    executor = engine.newNonAffinityThreadPool(numberOfClients);

    FixpSessionFactory fixpSessionFactory =
        new FixpSessionFactory(engine.getReactor(), clientConfig.getKeepaliveInterval(),
            clientConfig.isTransportMultiplexed());

    runners = new ClientRunner[numberOfClients];
    for (int i = 0; i < numberOfClients; ++i) {

      final Histogram rttHistogram =
          new Histogram(highestTrackableValue, numberOfSignificantValueDigits);

      final Session<UUID> client = createSession(i, fixpSessionFactory, rttHistogram);

      runners[i] =
          new ClientRunner(client, rttHistogram, ordersToSend, batchSize, ordersPerPacket,
              batchPauseMillis);
    }
  }

  public void run() {
    SessionTerminatedFuture[] terminatedFutures = new SessionTerminatedFuture[numberOfClients];
    for (int i = 0; i < numberOfClients; ++i) {
      terminatedFutures[i] = runners[i].connect();
    }

    CompletableFuture<Void> allDone = CompletableFuture.allOf(terminatedFutures);
    try {
      allDone.get();
      for (int i = 0; i < numberOfClients; ++i) {
        runners[i].report();
      }
    } catch (InterruptedException | ExecutionException e) {
      exceptionConsumer.accept(e);
    }

    shutdown();
  }

  /**
   * @param ordersToSend the ordersToSend to set
   */
  public void setOrdersToSend(int ordersToSend) {
    this.ordersToSend = ordersToSend;
  }


  public void shutdown() {
    System.out.println("Shutting down");
    engine.close();
    executor.shutdown();
    System.exit(0);
  }

  private Transport createRawTransport(String protocol, SocketAddress remoteAddress)
      throws Exception {
    Transport transport;
    switch (protocol) {
      case PROTOCOL_TCP:
        SocketAddress serverAddress;
        transport = new TcpConnectorTransport(engine.getIOReactor().getSelector(), remoteAddress);
        break;
      case PROTOCOL_SHARED_MEMORY:
        transport = new SharedMemoryTransport(true, 1, true, engine.getThreadFactory());
        break;
      default:
        throw new IOException("Unsupported protocol");
    }
    return transport;
  }

  private Transport createTransport(String protocol, boolean isMultiplexed,
      SocketAddress remoteAddress) throws Exception {
    Transport transport;
    if (isMultiplexed) {
      if (sharedTransport == null) {
        sharedTransport =
            FixpSharedTransportAdaptor
                .builder()
                .withReactor(engine.getReactor())
                .withTransport(createRawTransport(protocol, remoteAddress))
                .withBufferSupplier(
                    new SingleBufferSupplier(ByteBuffer.allocate(16 * 1024).order(
                        ByteOrder.nativeOrder()))).withFlowType(FlowType.IDEMPOTENT).build();
      }
      transport = sharedTransport;
    } else {
      transport = createRawTransport(protocol, remoteAddress);
    }

    return transport;
  }

}
