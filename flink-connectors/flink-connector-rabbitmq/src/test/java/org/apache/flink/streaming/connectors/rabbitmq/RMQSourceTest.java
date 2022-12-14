/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.rabbitmq;

import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.OperatorStateStore;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.connectors.rabbitmq.common.RMQConnectionConfig;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.streaming.util.MockDeserializationSchema;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.answers.CallsRealMethods;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * Tests for the RMQSource. The source supports two operation modes. 1) Exactly-once (when
 * checkpointed) with RabbitMQ transactions and the deduplication mechanism in {@link
 * org.apache.flink.streaming.api.functions.source.MessageAcknowledgingSourceBase}. 2) At-least-once
 * (when checkpointed) with RabbitMQ transactions but not deduplication. 3) No strong delivery
 * guarantees (without checkpointing) with RabbitMQ auto-commit mode.
 *
 * <p>This tests assumes that the message ids are increasing monotonously. That doesn't have to be
 * the case. The correlation id is used to uniquely identify messages.
 */
class RMQSourceTest {

    private RMQSource<String> source;

    private Configuration config = new Configuration();

    private Thread sourceThread;

    private volatile long messageId;

    private boolean generateCorrelationIds;

    private volatile Exception exception;

    /**
     * Gets a mock context for initializing the source's state via {@link
     * org.apache.flink.streaming.api.checkpoint.CheckpointedFunction#initializeState}.
     *
     * @throws Exception
     */
    FunctionInitializationContext getMockContext() throws Exception {
        OperatorStateStore mockStore = Mockito.mock(OperatorStateStore.class);
        FunctionInitializationContext mockContext =
                Mockito.mock(FunctionInitializationContext.class);
        Mockito.when(mockContext.getOperatorStateStore()).thenReturn(mockStore);
        Mockito.when(mockStore.getListState(any(ListStateDescriptor.class))).thenReturn(null);
        return mockContext;
    }

    @BeforeEach
    void beforeTest() throws Exception {
        FunctionInitializationContext mockContext = getMockContext();

        source = new RMQTestSource();
        source.initializeState(mockContext);
        source.open(config);

        DummySourceContext.numElementsCollected = 0;
        messageId = 0;
        generateCorrelationIds = true;

        sourceThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    source.run(new DummySourceContext());
                                } catch (Exception e) {
                                    exception = e;
                                }
                            }
                        });
    }

    @AfterEach
    void afterTest() throws Exception {
        source.cancel();
        sourceThread.join();
    }

    @Test
    void throwExceptionIfConnectionFactoryReturnNull() throws Exception {
        RMQConnectionConfig connectionConfig = Mockito.mock(RMQConnectionConfig.class);
        ConnectionFactory connectionFactory = Mockito.mock(ConnectionFactory.class);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(connectionConfig.getConnectionFactory()).thenReturn(connectionFactory);
        Mockito.when(connectionFactory.newConnection()).thenReturn(connection);
        Mockito.when(connection.createChannel()).thenReturn(null);

        RMQSource<String> rmqSource =
                new RMQSource<>(
                        connectionConfig, "queueDummy", true, new StringDeserializationScheme());
        assertThatThrownBy(() -> rmqSource.open(new Configuration()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("None of RabbitMQ channels are available");
    }

    @Test
    void testResourceCleanupOnOpenFailure() throws Exception {
        RMQConnectionConfig connectionConfig = Mockito.mock(RMQConnectionConfig.class);
        ConnectionFactory connectionFactory = Mockito.mock(ConnectionFactory.class);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(connectionConfig.getConnectionFactory()).thenReturn(connectionFactory);
        Mockito.when(connectionConfig.getHost()).thenReturn("hostDummy");
        Mockito.when(connectionFactory.newConnection()).thenReturn(connection);
        Mockito.when(connection.createChannel()).thenThrow(new IOException());

        RMQSource<String> rmqSource =
                new RMQSource<>(
                        connectionConfig, "queueDummy", true, new StringDeserializationScheme());
        assertThatThrownBy(() -> rmqSource.open(new Configuration()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cannot create RMQ connection with queueDummy at hostDummy");
        Mockito.verify(rmqSource.connection, Mockito.atLeastOnce()).close();
    }

    @Test
    void testOpenCallDeclaresQueueInStandardMode() throws Exception {
        FunctionInitializationContext mockContext = getMockContext();

        RMQConnectionConfig connectionConfig = Mockito.mock(RMQConnectionConfig.class);
        ConnectionFactory connectionFactory = Mockito.mock(ConnectionFactory.class);
        Connection connection = Mockito.mock(Connection.class);
        Channel channel = Mockito.mock(Channel.class);

        Mockito.when(connectionConfig.getConnectionFactory()).thenReturn(connectionFactory);
        Mockito.when(connectionFactory.newConnection()).thenReturn(connection);
        Mockito.when(connection.createChannel()).thenReturn(channel);

        RMQSource<String> rmqSource = new RMQMockedRuntimeTestSource(connectionConfig);
        rmqSource.initializeState(mockContext);
        rmqSource.open(new Configuration());

        Mockito.verify(channel).queueDeclare(RMQTestSource.QUEUE_NAME, true, false, false, null);
    }

    @Test
    void testResourceCleanupOnClose() throws Exception {
        FunctionInitializationContext mockContext = getMockContext();

        RMQConnectionConfig connectionConfig = Mockito.mock(RMQConnectionConfig.class);
        ConnectionFactory connectionFactory = Mockito.mock(ConnectionFactory.class);
        Connection connection = Mockito.mock(Connection.class);
        Channel channel = Mockito.mock(Channel.class);

        Mockito.when(connectionConfig.getConnectionFactory()).thenReturn(connectionFactory);
        Mockito.when(connectionFactory.newConnection()).thenReturn(connection);
        Mockito.when(connectionConfig.getHost()).thenReturn("hostDummy");
        Mockito.when(connection.createChannel()).thenReturn(channel);
        Mockito.doThrow(new IOException("Consumer cancel error")).when(channel).basicCancel(any());
        Mockito.doThrow(new IOException("Channel error")).when(channel).close();
        Mockito.doThrow(new IOException("Connection error")).when(connection).close();

        RMQSource<String> rmqSource = new RMQMockedRuntimeTestSource(connectionConfig);
        rmqSource.initializeState(mockContext);
        rmqSource.open(new Configuration());

        assertThatThrownBy(rmqSource::close)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Error while cancelling RMQ consumer on queueDummy at hostDummy")
                .extracting(Throwable::getSuppressed)
                .satisfies(t -> assertThat(t).hasSize(1))
                .satisfies(
                        t ->
                                assertThat(t[0])
                                        .hasMessage(
                                                "Error while closing RMQ source with queueDummy at hostDummy"));
        Mockito.verify(rmqSource.channel, Mockito.atLeastOnce()).basicCancel(any());
        Mockito.verify(rmqSource.channel, Mockito.atLeastOnce()).close();
        Mockito.verify(rmqSource.connection, Mockito.atLeastOnce()).close();
    }

    @Test
    void testCheckpointing() throws Exception {
        source.autoAck = false;

        StreamSource<String, RMQSource<String>> src = new StreamSource<>(source);
        AbstractStreamOperatorTestHarness<String> testHarness =
                new AbstractStreamOperatorTestHarness<>(src, 1, 1, 0);
        testHarness.open();

        sourceThread.start();

        Thread.sleep(5);

        final Random random = new Random(System.currentTimeMillis());
        int numSnapshots = 50;
        long previousSnapshotId;
        long lastSnapshotId = 0;

        long totalNumberOfAcks = 0;

        for (int i = 0; i < numSnapshots; i++) {
            long snapshotId = random.nextLong();
            OperatorSubtaskState data;

            synchronized (DummySourceContext.lock) {
                data = testHarness.snapshot(snapshotId, System.currentTimeMillis());
                previousSnapshotId = lastSnapshotId;
                lastSnapshotId = messageId;
            }
            // let some time pass
            Thread.sleep(5);

            // check if the correct number of messages have been snapshotted
            final long numIds = lastSnapshotId - previousSnapshotId;

            RMQTestSource sourceCopy = new RMQTestSource();
            StreamSource<String, RMQTestSource> srcCopy = new StreamSource<>(sourceCopy);
            AbstractStreamOperatorTestHarness<String> testHarnessCopy =
                    new AbstractStreamOperatorTestHarness<>(srcCopy, 1, 1, 0);

            testHarnessCopy.setup();
            testHarnessCopy.initializeState(data);
            testHarnessCopy.open();

            ArrayDeque<Tuple2<Long, Set<String>>> deque = sourceCopy.getRestoredState();
            Set<String> messageIds = deque.getLast().f1;

            assertThat(messageIds).hasSize((int) numIds);
            if (messageIds.size() > 0) {
                assertThat(messageIds).contains(Long.toString(lastSnapshotId - 1));
            }

            // check if the messages are being acknowledged and the transaction committed
            synchronized (DummySourceContext.lock) {
                source.notifyCheckpointComplete(snapshotId);
            }
            totalNumberOfAcks += numIds;
        }

        Mockito.verify(source.channel, Mockito.times((int) totalNumberOfAcks))
                .basicAck(Mockito.anyLong(), Mockito.eq(false));
        Mockito.verify(source.channel, Mockito.times(numSnapshots)).txCommit();
    }

    /** Checks whether recurring ids are processed again (they shouldn't be). */
    @Test
    void testDuplicateId() throws Exception {
        source.autoAck = false;
        sourceThread.start();

        while (messageId < 10) {
            // wait until messages have been processed
            Thread.sleep(5);
        }

        long oldMessageId;
        synchronized (DummySourceContext.lock) {
            oldMessageId = messageId;
            messageId = 0;
        }

        while (messageId < 10) {
            // process again
            Thread.sleep(5);
        }

        synchronized (DummySourceContext.lock) {
            assertThat(Math.max(messageId, oldMessageId))
                    .isEqualTo(DummySourceContext.numElementsCollected);
        }
    }

    /**
     * The source should not acknowledge ids in auto-commit mode or check for previously
     * acknowledged ids.
     */
    @Test
    void testCheckpointingDisabled() throws Exception {
        source.autoAck = true;
        sourceThread.start();

        while (DummySourceContext.numElementsCollected < 50) {
            // wait until messages have been processed
            Thread.sleep(5);
        }

        // verify if RMQTestSource#addId was never called
        assertThat(((RMQTestSource) source).addIdCalls).isEqualTo(0);
    }

    /** Tests error reporting in case of invalid correlation ids. */
    @Test
    void testCorrelationIdNotSet() throws InterruptedException {
        generateCorrelationIds = false;
        source.autoAck = false;
        sourceThread.start();

        sourceThread.join();

        assertThat(exception).isInstanceOf(NullPointerException.class);
    }

    /** Tests whether redelivered messages are acknowledged properly. */
    @Test
    void testRedeliveredSessionIDsAck() throws Exception {
        source.autoAck = false;

        StreamSource<String, RMQSource<String>> src = new StreamSource<>(source);
        AbstractStreamOperatorTestHarness<String> testHarness =
                new AbstractStreamOperatorTestHarness<>(src, 1, 1, 0);
        testHarness.open();
        sourceThread.start();

        while (DummySourceContext.numElementsCollected < 10) {
            // wait until messages have been processed
            Thread.sleep(5);
        }

        // mock message redelivery by resetting the message ID
        long numMsgRedelivered;
        synchronized (DummySourceContext.lock) {
            numMsgRedelivered = DummySourceContext.numElementsCollected;
            messageId = 0;
        }
        while (DummySourceContext.numElementsCollected < numMsgRedelivered + 10) {
            // wait until some messages will be redelivered
            Thread.sleep(5);
        }

        // ack the messages by snapshotting the state
        final Random random = new Random(System.currentTimeMillis());
        long lastMessageId;
        long snapshotId = random.nextLong();
        synchronized (DummySourceContext.lock) {
            testHarness.snapshot(snapshotId, System.currentTimeMillis());
            source.notifyCheckpointComplete(snapshotId);
            lastMessageId = messageId;

            // check if all the messages are being collected and acknowledged
            long totalNumberOfAcks = numMsgRedelivered + lastMessageId;
            assertThat(lastMessageId).isEqualTo(DummySourceContext.numElementsCollected);
            assertThat(totalNumberOfAcks).isEqualTo(((RMQTestSource) source).addIdCalls);
        }

        // check if all the acks are being sent
        Mockito.verify(source.channel, Mockito.times((int) lastMessageId))
                .basicAck(Mockito.anyLong(), Mockito.eq(false));
        Mockito.verify(source.channel, Mockito.times((int) numMsgRedelivered))
                .basicReject(Mockito.anyLong(), Mockito.eq(false));
    }

    /** Tests whether constructor params are passed correctly. */
    @Test
    void testConstructorParams() throws Exception {
        // verify construction params
        RMQConnectionConfig.Builder builder = new RMQConnectionConfig.Builder();
        builder.setHost("hostTest")
                .setPort(999)
                .setUserName("userTest")
                .setPassword("passTest")
                .setVirtualHost("/");
        ConstructorTestClass testObj =
                new ConstructorTestClass(
                        builder.build(), "queueTest", false, new StringDeserializationScheme());

        try {
            testObj.open(new Configuration());
        } catch (Exception e) {
            // connection fails but check if args have been passed correctly
        }

        assertThat(testObj.getFactory().getHost()).isEqualTo("hostTest");
        assertThat(testObj.getFactory().getPort()).isEqualTo(999);
        assertThat(testObj.getFactory().getUsername()).isEqualTo("userTest");
        assertThat(testObj.getFactory().getPassword()).isEqualTo("passTest");
    }

    @Test
    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    void testCustomIdentifiers() throws Exception {
        source = new RMQTestSource(new CustomDeserializationSchema());
        source.initializeState(getMockContext());
        source.open(config);
        sourceThread.start();
        sourceThread.join();

        assertThat(DummySourceContext.numElementsCollected).isEqualTo(2L);
    }

    @Test
    void testOpen() throws Exception {
        MockDeserializationSchema<String> deserializationSchema = new MockDeserializationSchema<>();

        RMQSource<String> consumer = new RMQTestSource(deserializationSchema);
        AbstractStreamOperatorTestHarness<String> testHarness =
                new AbstractStreamOperatorTestHarness<>(new StreamSource<>(consumer), 1, 1, 0);

        testHarness.open();
        assertThat(deserializationSchema.isOpenCalled()).as("Open method was not called").isTrue();
    }

    @Test
    void testOverrideConnection() throws Exception {
        final Connection mockConnection = Mockito.mock(Connection.class);
        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(mockConnection.createChannel()).thenReturn(channel);

        RMQMockedRuntimeTestSource source =
                new RMQMockedRuntimeTestSource() {
                    @Override
                    protected Connection setupConnection() throws Exception {
                        return mockConnection;
                    }
                };

        FunctionInitializationContext mockContext = getMockContext();
        source.initializeState(mockContext);
        source.open(new Configuration());

        Mockito.verify(mockConnection, Mockito.times(1)).createChannel();
    }

    @Test
    void testSetPrefetchCount() throws Exception {
        RMQConnectionConfig connectionConfig =
                new RMQConnectionConfig.Builder()
                        .setHost("localhost")
                        .setPort(5000)
                        .setUserName("guest")
                        .setPassword("guest")
                        .setVirtualHost("/")
                        .setPrefetchCount(1000)
                        .build();
        final Connection mockConnection = Mockito.mock(Connection.class);
        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(mockConnection.createChannel()).thenReturn(channel);

        RMQMockedRuntimeTestSource source =
                new RMQMockedRuntimeTestSource(connectionConfig) {
                    @Override
                    protected Connection setupConnection() throws Exception {
                        return mockConnection;
                    }
                };

        FunctionInitializationContext mockContext = getMockContext();
        source.initializeState(mockContext);
        source.open(new Configuration());

        Mockito.verify(mockConnection, Mockito.times(1)).createChannel();
        Mockito.verify(channel, Mockito.times(1)).basicQos(1000, true);
    }

    @Test
    void testUnsetPrefetchCount() throws Exception {
        final Connection mockConnection = Mockito.mock(Connection.class);
        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(mockConnection.createChannel()).thenReturn(channel);

        RMQMockedRuntimeTestSource source =
                new RMQMockedRuntimeTestSource() {
                    @Override
                    protected Connection setupConnection() throws Exception {
                        return mockConnection;
                    }
                };

        FunctionInitializationContext mockContext = getMockContext();
        source.initializeState(mockContext);
        source.open(new Configuration());

        Mockito.verify(mockConnection, Mockito.times(1)).createChannel();
        Mockito.verify(channel, Mockito.times(0)).basicQos(anyInt());
    }

    @Test
    void testDeliveryTimeout() throws Exception {
        source.autoAck = false;
        // mock not delivering messages
        CallsRealMethodsWithLatch delivery = new CallsRealMethodsWithLatch();
        Mockito.when(source.consumer.nextDelivery(any(Long.class))).then(delivery);

        sourceThread.start();
        // wait until message delivery starts
        delivery.awaitInvoke();

        source.cancel();
        sourceThread.join();
        Mockito.verify(source.consumer, Mockito.never()).nextDelivery();
        Mockito.verify(source.consumer, Mockito.atLeastOnce()).nextDelivery(any(Long.class));
        assertThat(exception).isNull();
    }

    private static class CallsRealMethodsWithLatch extends CallsRealMethods {

        private final CountDownLatch latch = new CountDownLatch(1);

        public void awaitInvoke() throws InterruptedException {
            latch.await();
        }

        public Object answer(InvocationOnMock invocation) throws Throwable {
            latch.countDown();
            return super.answer(invocation);
        }
    }

    private static class ConstructorTestClass extends RMQSource<String> {

        private ConnectionFactory factory;

        public ConstructorTestClass(
                RMQConnectionConfig rmqConnectionConfig,
                String queueName,
                boolean usesCorrelationId,
                DeserializationSchema<String> deserializationSchema)
                throws Exception {
            super(rmqConnectionConfig, queueName, usesCorrelationId, deserializationSchema);
            RMQConnectionConfig.Builder builder = new RMQConnectionConfig.Builder();
            builder.setHost("hostTest")
                    .setPort(999)
                    .setUserName("userTest")
                    .setPassword("passTest")
                    .setVirtualHost("/");
            factory = Mockito.spy(builder.build().getConnectionFactory());
            try {
                Mockito.doThrow(new RuntimeException()).when(factory).newConnection();
            } catch (IOException e) {
                fail("Failed to stub connection method");
            }
        }

        @Override
        protected ConnectionFactory setupConnectionFactory() {
            return factory;
        }

        public ConnectionFactory getFactory() {
            return factory;
        }
    }

    private static class StringDeserializationScheme implements DeserializationSchema<String> {

        @Override
        public String deserialize(byte[] message) throws IOException {
            try {
                // wait a bit to not cause too much cpu load
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return new String(message, ConfigConstants.DEFAULT_CHARSET);
        }

        @Override
        public boolean isEndOfStream(String nextElement) {
            return false;
        }

        @Override
        public TypeInformation<String> getProducedType() {
            return TypeExtractor.getForClass(String.class);
        }
    }

    private static class CustomDeserializationSchema implements RMQDeserializationSchema<String> {
        @Override
        public TypeInformation<String> getProducedType() {
            return TypeExtractor.getForClass(String.class);
        }

        @Override
        public void deserialize(
                Envelope envelope,
                AMQP.BasicProperties properties,
                byte[] body,
                RMQCollector<String> collector) {
            String correlationId = properties.getCorrelationId();
            if (correlationId.equals("1")) {
                collector.setMessageIdentifiers("1", envelope.getDeliveryTag());
                collector.collect("I Love Turtles");
                collector.collect("Brush your teeth");
            } else if (correlationId.equals("2")) {
                // should not be emitted, because it has the same correlationId as the previous one
                collector.setMessageIdentifiers("1", envelope.getDeliveryTag());
                collector.collect("Brush your teeth");
            } else {
                // should end the stream, should not be emitted
                collector.setMessageIdentifiers("2", envelope.getDeliveryTag());
                collector.collect("FINISH");
            }
        }

        @Override
        public boolean isEndOfStream(String record) {
            return record.equals("FINISH");
        }
    }

    /**
     * A base class of {@link RMQTestSource} for testing functions that rely on the {@link
     * RuntimeContext}.
     */
    private static class RMQMockedRuntimeTestSource extends RMQSource<String> {
        static final String QUEUE_NAME = "queueDummy";

        static final RMQConnectionConfig CONNECTION_CONFIG =
                new RMQConnectionConfig.Builder()
                        .setHost("hostTest")
                        .setPort(999)
                        .setUserName("userTest")
                        .setPassword("passTest")
                        .setVirtualHost("/")
                        .setDeliveryTimeout(100)
                        .build();

        protected RuntimeContext runtimeContext = Mockito.mock(StreamingRuntimeContext.class);

        public RMQMockedRuntimeTestSource(
                RMQConnectionConfig connectionConfig,
                DeserializationSchema<String> deserializationSchema) {
            super(connectionConfig, QUEUE_NAME, true, deserializationSchema);
        }

        public RMQMockedRuntimeTestSource(
                RMQConnectionConfig connectionConfig,
                RMQDeserializationSchema<String> deliveryParser) {
            super(connectionConfig, QUEUE_NAME, true, deliveryParser);
        }

        public RMQMockedRuntimeTestSource(DeserializationSchema<String> deserializationSchema) {
            this(CONNECTION_CONFIG, deserializationSchema);
        }

        public RMQMockedRuntimeTestSource(RMQDeserializationSchema<String> deliveryParser) {
            this(CONNECTION_CONFIG, deliveryParser);
        }

        public RMQMockedRuntimeTestSource(RMQConnectionConfig connectionConfig) {
            this(connectionConfig, new StringDeserializationScheme());
        }

        public RMQMockedRuntimeTestSource() {
            this(new StringDeserializationScheme());
        }

        @Override
        public RuntimeContext getRuntimeContext() {
            return runtimeContext;
        }
    }

    private class RMQTestSource extends RMQMockedRuntimeTestSource {
        private ArrayDeque<Tuple2<Long, Set<String>>> restoredState;

        private Delivery mockedDelivery;
        public Envelope mockedAMQPEnvelope;
        public AMQP.BasicProperties mockedAMQPProperties;
        public int addIdCalls = 0;

        public RMQTestSource() {
            super();
        }

        public RMQTestSource(DeserializationSchema<String> deserializationSchema) {
            super(deserializationSchema);
        }

        public RMQTestSource(RMQDeserializationSchema<String> deliveryParser) {
            super(deliveryParser);
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            super.initializeState(context);
            this.restoredState = this.pendingCheckpoints;
        }

        public ArrayDeque<Tuple2<Long, Set<String>>> getRestoredState() {
            return this.restoredState;
        }

        public void initAMQPMocks() {
            consumer = Mockito.mock(QueueingConsumer.class);

            // Mock for delivery
            mockedDelivery = Mockito.mock(Delivery.class);
            Mockito.when(mockedDelivery.getBody())
                    .thenReturn("test".getBytes(ConfigConstants.DEFAULT_CHARSET));

            try {
                Mockito.when(consumer.nextDelivery(any(Long.class))).thenReturn(mockedDelivery);
            } catch (InterruptedException e) {
                fail("Couldn't setup up deliveryMock");
            }

            // Mock for envelope
            mockedAMQPEnvelope = Mockito.mock(Envelope.class);
            Mockito.when(mockedDelivery.getEnvelope()).thenReturn(mockedAMQPEnvelope);

            Mockito.when(mockedAMQPEnvelope.getDeliveryTag())
                    .thenAnswer(
                            new Answer<Long>() {
                                @Override
                                public Long answer(InvocationOnMock invocation) throws Throwable {
                                    return ++messageId;
                                }
                            });

            // Mock for properties
            mockedAMQPProperties = Mockito.mock(AMQP.BasicProperties.class);
            Mockito.when(mockedDelivery.getProperties()).thenReturn(mockedAMQPProperties);

            Mockito.when(mockedAMQPProperties.getCorrelationId())
                    .thenAnswer(
                            new Answer<String>() {
                                @Override
                                public String answer(InvocationOnMock invocation) throws Throwable {
                                    return generateCorrelationIds ? "" + messageId : null;
                                }
                            });

            Mockito.when(mockedAMQPProperties.getMessageId())
                    .thenAnswer(
                            new Answer<String>() {
                                @Override
                                public String answer(InvocationOnMock invocation) throws Throwable {
                                    return messageId + "-MESSAGE_ID";
                                }
                            });
        }

        @Override
        public void open(Configuration config) throws Exception {
            super.open(config);
            initAMQPMocks();
        }

        @Override
        protected ConnectionFactory setupConnectionFactory() {
            ConnectionFactory connectionFactory = Mockito.mock(ConnectionFactory.class);
            Connection connection = Mockito.mock(Connection.class);
            try {
                Mockito.when(connectionFactory.newConnection()).thenReturn(connection);
                Mockito.when(connection.createChannel()).thenReturn(Mockito.mock(Channel.class));
            } catch (IOException | TimeoutException e) {
                fail("Test environment couldn't be created.");
            }
            return connectionFactory;
        }

        @Override
        public void setRuntimeContext(RuntimeContext t) {
            this.runtimeContext = t;
        }

        @Override
        protected boolean addId(String uid) {
            addIdCalls++;
            assertThat(autoAck).isFalse();
            return super.addId(uid);
        }
    }

    private static class DummySourceContext implements SourceFunction.SourceContext<String> {

        private static final Object lock = new Object();

        private static long numElementsCollected;

        public DummySourceContext() {
            numElementsCollected = 0;
        }

        @Override
        public void collect(String element) {
            numElementsCollected++;
        }

        @Override
        public void collectWithTimestamp(java.lang.String element, long timestamp) {}

        @Override
        public void emitWatermark(Watermark mark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAsTemporarilyIdle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getCheckpointLock() {
            return lock;
        }

        @Override
        public void close() {}
    }
}
