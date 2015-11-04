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
package org.fixtrading.silverflash.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.fixtrading.silverflash.Service;
import org.fixtrading.silverflash.buffer.BufferSupplier;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

/**
 * @author Donald
 *
 */
public class BufferedTransportConsumer implements BufferSupplier, Service, TransportConsumer {

  private static class BufferEvent {
    private static int capacity = 2048;

    static final EventFactory<BufferEvent> EVENT_FACTORY = BufferEvent::new;
    private final ByteBuffer buffer;
    private EventType eventType;

    BufferEvent() {
      buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
      eventType = EventType.DATA;
    }

    ByteBuffer getBuffer() {
      return buffer;
    }

    /**
     * @return the eventType
     */
    public EventType getEventType() {
      return eventType;
    }

    /**
     * @param connected
     */
    public void set(EventType eventType) {
      this.eventType = eventType;
      buffer.clear();
    }

    void set(EventType eventType, ByteBuffer source) {
      this.eventType = eventType;
      buffer.clear();
      if (source != null) {
        buffer.put(source);
        buffer.flip();
      }
    }
  }

  private enum EventType {
    CONNECTED, DATA, DISCONNECTED
  }

  private TransportConsumer consumer;
  private Disruptor<BufferEvent> disruptor;
  private final Executor executor;
  private final AtomicBoolean isRunning = new AtomicBoolean();
  private RingBuffer<BufferEvent> ringBuffer;
  private final int ringSize = 256;

  private final ThreadLocal<Long> uncommitted = new ThreadLocal<Long>() {
    @Override
    protected Long initialValue() {
      return 0L;
    }
  };

  /**
   * @param executor
   */
  public BufferedTransportConsumer(Executor executor, TransportConsumer consumer) {
    this.executor = executor;
    this.consumer = consumer;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.util.function.Consumer#accept(java.lang.Object)
   */
  @Override
  public void accept(ByteBuffer source) {
    long sequence = ringBuffer.next();
    final BufferEvent event = ringBuffer.get(sequence);
    event.set(EventType.DATA, source);
    ringBuffer.publish(sequence);
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.AutoCloseable#close()
   */
  @Override
  public void close() throws Exception {
    if (isRunning.compareAndSet(true, false)) {
      this.disruptor.halt();
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.buffer.BufferSupplier#commit()
   */
  @Override
  public void commit() {
    ringBuffer.publish(uncommitted.get());
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.transport.TransportConsumer#connected()
   */
  @Override
  public void connected() {
    long sequence = ringBuffer.next();
    final BufferEvent event = ringBuffer.get(sequence);
    event.set(EventType.CONNECTED);
    ringBuffer.publish(sequence);
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.transport.TransportConsumer#disconnected()
   */
  @Override
  public void disconnected() {
    if (ringBuffer != null) {
      long sequence = ringBuffer.next();
      final BufferEvent event = ringBuffer.get(sequence);
      event.set(EventType.CONNECTED);
      ringBuffer.publish(sequence);
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.util.function.Supplier#get()
   */
  @Override
  public ByteBuffer get() {
    long sequence = ringBuffer.next();
    final BufferEvent event = ringBuffer.get(sequence);
    event.set(EventType.DATA);
    uncommitted.set(sequence);
    return event.getBuffer();
  }

  private void onEvent(BufferEvent event, long sequence, boolean endOfBatch) throws Exception {
    switch (event.getEventType()) {
    case DATA:
      ByteBuffer buffer = event.getBuffer();
      buffer.flip();
      consumer.accept(buffer);
      break;
    case CONNECTED:
      consumer.connected();
      break;
    case DISCONNECTED:
      consumer.disconnected();
      break;
    }
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.fixtrading.silverflash.Service#open()
   */
  @Override
  public CompletableFuture<? extends Service> open() {
    if (isRunning.compareAndSet(false, true)) {
      this.disruptor = new Disruptor<>(BufferEvent.EVENT_FACTORY, ringSize, executor,
          ProducerType.SINGLE, new BusySpinWaitStrategy());
      this.disruptor.handleEventsWith(this::onEvent);
      this.ringBuffer = disruptor.start();
    }
    return CompletableFuture.completedFuture(this);
  }
}