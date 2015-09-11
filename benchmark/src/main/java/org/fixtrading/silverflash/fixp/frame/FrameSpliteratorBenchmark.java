package org.fixtrading.silverflash.fixp.frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import org.fixtrading.silverflash.fixp.frame.FixpWithMessageLengthFrameSpliterator;
import org.fixtrading.silverflash.fixp.messages.MessageHeaderWithFrame;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class FrameSpliteratorBenchmark {

  @Param({"1", "2", "4", "8"})
  public int numberOfMessages;


  @Param({"128", "256", "1024"})
  public int messageLength;

  private final int schemaVersion = 1;
  private final int schemaId = 66;
  private final int templateId = 77;

  private FixpWithMessageLengthFrameSpliterator spliterator;
  private MessageHeaderWithFrame messageHeader;
  private ByteBuffer buffer;

  @AuxCounters
  @State(Scope.Thread)
  public static class Counters {
    public int failed;
    public int succeeded;

    @Setup(Level.Iteration)
    public void clean() {
      failed = 0;
      succeeded = 0;
    }
  }

  @Setup
  public void initTestEnvironment() throws Exception {
    buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.nativeOrder());
    ByteBuffer message =
        ByteBuffer.allocate(messageLength - MessageHeaderWithFrame.getLength()).order(
            ByteOrder.nativeOrder());
    for (int i = 0; i < message.limit(); i++) {
      message.put((byte) i);
    }

    for (int i = 0; i < numberOfMessages; i++) {
      encodeApplicationMessage(buffer, message);
    }
    messageHeader = new MessageHeaderWithFrame();
    spliterator = new FixpWithMessageLengthFrameSpliterator();
  }

  @Benchmark
  public void parse(Counters counters) throws IOException {
    buffer.rewind();
    spliterator.wrap(buffer);

    while (spliterator.tryAdvance(new Consumer<ByteBuffer>() {

      public void accept(ByteBuffer message) {
        messageHeader.attachForDecode(buffer, message.position());

        if (templateId == messageHeader.getTemplateId()) {
          counters.succeeded++;
        } else {
          counters.failed++;
        }

      }
    }));
  }

  private void encodeApplicationMessage(ByteBuffer buf, ByteBuffer message) {
    message.rewind();
    int messageLength = message.remaining();
    MessageHeaderWithFrame.encode(buf, buf.position(), messageLength, templateId, schemaId,
        schemaVersion, messageLength + MessageHeaderWithFrame.getLength());

    buf.put(message);
  }
}
