/*
 * Copyright 2014-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.driver.ext.DebugChannelEndpointConfiguration;
import io.aeron.driver.ext.DebugSendChannelEndpoint;
import io.aeron.driver.ext.LossGenerator;
import io.aeron.exceptions.RegistrationException;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.RawBlockHandler;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SlowTest;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.Tests;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.BitUtil;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.aeron.SystemTests.verifyLossOccurredForStream;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static java.util.Arrays.asList;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(InterruptingTestCallback.class)
class PubAndSubTest
{
    private static final String IPC_URI = "aeron:ipc";

    private static List<String> channels()
    {
        return asList(
            "aeron:udp?endpoint=localhost:24325",
            "aeron:udp?endpoint=224.20.30.39:24326|interface=localhost",
            IPC_URI);
    }

    @RegisterExtension
    final SystemTestWatcher watcher = new SystemTestWatcher();

    private static final int STREAM_ID = 1001;
    private static final ThreadingMode THREADING_MODE = ThreadingMode.SHARED;

    private final MediaDriver.Context context = new MediaDriver.Context();

    private Aeron publishingClient;
    private Aeron subscribingClient;
    private TestMediaDriver driver;
    private Subscription subscription;
    private Publication publication;

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[8192]);
    private final FragmentHandler fragmentHandler = mock(FragmentHandler.class);
    private final RawBlockHandler rawBlockHandler = mock(RawBlockHandler.class);

    private void launch(final String channel)
    {
        context
            .threadingMode(THREADING_MODE)
            .publicationConnectionTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500))
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100));

        driver = TestMediaDriver.launch(context, watcher);
        watcher.dataCollector().add(driver.context().aeronDirectory());

        subscribingClient = Aeron.connect();
        publishingClient = Aeron.connect();
        subscription = subscribingClient.addSubscription(channel, STREAM_ID);
        publication = publishingClient.addPublication(channel, STREAM_ID);
    }

    @AfterEach
    void after()
    {
        CloseHelper.closeAll(publishingClient, subscribingClient, driver);
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldReceivePublishedMessageViaPollFile(final String channel)
    {
        launch(channel);

        publishMessage();

        while (true)
        {
            final long bytes = subscription.rawPoll(rawBlockHandler, Integer.MAX_VALUE);
            if (bytes > 0)
            {
                break;
            }

            Tests.yield();
        }

        final long expectedOffset = 0L;
        final int expectedLength = BitUtil.align(HEADER_LENGTH + SIZE_OF_INT, FRAME_ALIGNMENT);

        final ArgumentCaptor<FileChannel> channelArgumentCaptor = ArgumentCaptor.forClass(FileChannel.class);
        verify(rawBlockHandler).onBlock(
            channelArgumentCaptor.capture(),
            eq(expectedOffset),
            any(UnsafeBuffer.class),
            eq((int)expectedOffset),
            eq(expectedLength),
            anyInt(),
            anyInt());

        assertTrue(channelArgumentCaptor.getValue().isOpen(), "File Channel is closed");
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldContinueAfterBufferRollover(final String channel)
    {
        final int termBufferLength = 64 * 1024;
        final int numMessagesInTermBuffer = 64;
        final int messageLength = (termBufferLength / numMessagesInTermBuffer) - HEADER_LENGTH;
        final int numMessagesToSend = numMessagesInTermBuffer + 1;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }

            pollForFragment();
        }

        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldContinueAfterRolloverWithMinimalPaddingHeader(final String channel)
    {
        final int termBufferLength = 64 * 1024;
        final int termBufferLengthMinusPaddingHeader = termBufferLength - HEADER_LENGTH;
        final int num1kMessagesInTermBuffer = 63;
        final int lastMessageLength =
            termBufferLengthMinusPaddingHeader - (num1kMessagesInTermBuffer * 1024) - HEADER_LENGTH;
        final int messageLength = 1024 - HEADER_LENGTH;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        // lock step reception until we get to within 8 messages of the end
        for (int i = 0; i < num1kMessagesInTermBuffer - 7; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }

            pollForFragment();
        }

        for (int i = 7; i > 0; i--)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }
        }

        // small enough to leave room for padding that is just a header
        while (publication.offer(buffer, 0, lastMessageLength) < 0L)
        {
            Tests.yield();
        }

        // no roll over
        while (publication.offer(buffer, 0, messageLength) < 0L)
        {
            Tests.yield();
        }

        final MutableInteger fragmentsRead = new MutableInteger();

        Tests.executeUntil(
            () -> fragmentsRead.value == 9,
            (j) ->
            {
                final int fragments = subscription.poll(fragmentHandler, 10);
                if (0 == fragments)
                {
                    Thread.yield();
                }
                fragmentsRead.value += fragments;
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(500));

        final InOrder inOrder = inOrder(fragmentHandler);

        inOrder.verify(fragmentHandler, times(num1kMessagesInTermBuffer)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
        inOrder.verify(fragmentHandler, times(1)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(lastMessageLength),
            any(Header.class));
        inOrder.verify(fragmentHandler, times(1)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(20)
    @SlowTest
    void shouldReceivePublishedMessageOneForOneWithDataLoss(final String channel) throws IOException
    {
        assumeFalse(IPC_URI.equals(channel));

        final int termBufferLength = 64 * 1024;
        final int numMessagesInTermBuffer = 64;
        final int messageLength = (termBufferLength / numMessagesInTermBuffer) - HEADER_LENGTH;
        final int numMessagesToSend = 2 * numMessagesInTermBuffer;

        final LossGenerator noLossGenerator =
            DebugChannelEndpointConfiguration.lossGeneratorSupplier(0, 0);

        context.publicationTermBufferLength(termBufferLength);

        context.sendChannelEndpointSupplier((udpChannel, statusIndicator, context) -> new DebugSendChannelEndpoint(
            udpChannel, statusIndicator, context, noLossGenerator, noLossGenerator));

        TestMediaDriver.enableLossGenerationOnReceive(context, 0.1, 0xcafebabeL, true, false);

        launch(channel);

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }

            pollForFragment();
        }

        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));

        verifyLossOccurredForStream(context.aeronDirectoryName(), STREAM_ID);
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldReceivePublishedMessageBatchedWithDataLoss(final String channel) throws IOException
    {
        assumeFalse(IPC_URI.equals(channel));

        final int termBufferLength = 64 * 1024;
        final int numMessagesInTermBuffer = 64;
        final int messageLength = (termBufferLength / numMessagesInTermBuffer) - HEADER_LENGTH;
        final int numMessagesToSend = 2 * numMessagesInTermBuffer;
        final int numBatches = 4;
        final int numMessagesPerBatch = numMessagesToSend / numBatches;

        final LossGenerator noLossGenerator =
            DebugChannelEndpointConfiguration.lossGeneratorSupplier(0, 0);

        context.publicationTermBufferLength(termBufferLength);

        context.sendChannelEndpointSupplier((udpChannel, statusIndicator, context) -> new DebugSendChannelEndpoint(
            udpChannel, statusIndicator, context, noLossGenerator, noLossGenerator));

        TestMediaDriver.enableLossGenerationOnReceive(context, 0.1, 0xcafebabeL, true, false);

        launch(channel);

        for (int i = 0; i < numBatches; i++)
        {
            for (int j = 0; j < numMessagesPerBatch; j++)
            {
                while (publication.offer(buffer, 0, messageLength) < 0L)
                {
                    Tests.yield();
                }
            }

            pollForBatch(numMessagesPerBatch);
        }

        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));

        verifyLossOccurredForStream(context.aeronDirectoryName(), STREAM_ID);
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldContinueAfterBufferRolloverBatched(final String channel)
    {
        final int termBufferLength = 64 * 1024;
        final int numBatchesPerTerm = 4;
        final int numMessagesPerBatch = 16;
        final int numMessagesInTermBuffer = numMessagesPerBatch * numBatchesPerTerm;
        final int messageLength = (termBufferLength / numMessagesInTermBuffer) - HEADER_LENGTH;
        final int numMessagesToSend = numMessagesInTermBuffer + 1;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        for (int i = 0; i < numBatchesPerTerm; i++)
        {
            for (int j = 0; j < numMessagesPerBatch; j++)
            {
                while (publication.offer(buffer, 0, messageLength) < 0L)
                {
                    Tests.yield();
                }
            }

            pollForBatch(numMessagesPerBatch);
        }

        while (publication.offer(buffer, 0, messageLength) < 0L)
        {
            Tests.yield();
        }

        final MutableInteger fragmentsRead = new MutableInteger();

        Tests.executeUntil(
            () -> fragmentsRead.value > 0,
            (j) ->
            {
                final int fragments = subscription.poll(fragmentHandler, 10);
                if (0 == fragments)
                {
                    Thread.yield();
                }
                fragmentsRead.value += fragments;
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(900));

        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldContinueAfterBufferRolloverWithPadding(final String channel)
    {
        /*
         * 65536 bytes in the buffer
         * 63 * 1032 = 65016
         * 65536 - 65016 = 520 bytes padding at the end
         * so, sending 64 messages causes last to overflow
         */
        final int termBufferLength = 64 * 1024;
        final int messageLength = 1032 - HEADER_LENGTH;
        final int numMessagesToSend = 64;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }

            pollForFragment();
        }

        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldContinueAfterBufferRolloverWithPaddingBatched(final String channel)
    {
        /*
         * 65536 bytes in the buffer
         * 63 * 1032 = 65016
         * 65536 - 65016 = 520 bytes padding at the end
         * so, sending 64 messages causes last to overflow
         */
        final int termBufferLength = 64 * 1024;
        final int messageLength = 1032 - HEADER_LENGTH;
        final int numMessagesToSend = 64;
        final int numBatchesPerTerm = 4;
        final int numMessagesPerBatch = numMessagesToSend / numBatchesPerTerm;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        for (int i = 0; i < numBatchesPerTerm; i++)
        {
            for (int j = 0; j < numMessagesPerBatch; j++)
            {
                while (publication.offer(buffer, 0, messageLength) < 0L)
                {
                    Tests.yield();
                }
            }

            pollForBatch(numMessagesPerBatch);
        }

        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldReceiveOnlyAfterSendingUpToFlowControlLimit(final String channel)
    {
        /*
         * The subscriber will flow control before an entire term buffer. So, send until can't send anymore.
         * Then start up subscriber to drain.
         */
        final int termBufferLength = 64 * 1024;
        final int numMessagesPerTerm = 64;
        final int messageLength = (termBufferLength / numMessagesPerTerm) - HEADER_LENGTH;
        final int maxFails = 10000;
        int messagesSent = 0;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        for (int i = 0; i < numMessagesPerTerm; i++)
        {
            int offerFails = 0;

            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                if (++offerFails > maxFails)
                {
                    break;
                }

                Tests.yield();
            }

            if (offerFails > maxFails)
            {
                break;
            }

            messagesSent++;
        }

        final MutableInteger fragmentsRead = new MutableInteger();
        final int messagesToReceive = messagesSent;

        Tests.executeUntil(
            () -> fragmentsRead.value >= messagesToReceive,
            (j) ->
            {
                final int fragments = subscription.poll(fragmentHandler, 10);
                if (0 == fragments)
                {
                    Thread.yield();
                }
                fragmentsRead.value += fragments;
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(500));

        verify(fragmentHandler, times(messagesToReceive)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(messageLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldReceivePublishedMessageOneForOneWithReSubscription(final String channel)
    {
        final int termBufferLength = 64 * 1024;
        final int numMessagesInTermBuffer = 64;
        final int messageLength = (termBufferLength / numMessagesInTermBuffer) - HEADER_LENGTH;
        final int numMessagesToSendStageOne = numMessagesInTermBuffer / 2;
        final int numMessagesToSendStageTwo = numMessagesInTermBuffer;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        Tests.awaitConnected(subscription);

        for (int i = 0; i < numMessagesToSendStageOne; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }

            pollForFragment();
        }

        assertEquals(publication.position(), subscription.imageAtIndex(0).position());

        subscription.close();
        subscription = Tests.reAddSubscription(subscribingClient, channel, STREAM_ID);

        Tests.awaitConnected(subscription);

        for (int i = 0; i < numMessagesToSendStageTwo; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }

            pollForFragment();
        }

        assertEquals(publication.position(), subscription.imageAtIndex(0).position());
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldFragmentExactMessageLengthsCorrectly(final String channel)
    {
        final int termBufferLength = 64 * 1024;
        final int numFragmentsPerMessage = 2;
        final int mtuLength = context.mtuLength();
        final int frameLength = mtuLength - HEADER_LENGTH;
        final int messageLength = frameLength * numFragmentsPerMessage;
        final int numMessagesToSend = 2;
        final int numFramesToExpect = numMessagesToSend * numFragmentsPerMessage;

        context.publicationTermBufferLength(termBufferLength);

        launch(channel);

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, messageLength) < 0L)
            {
                Tests.yield();
            }
        }

        final MutableInteger fragmentsRead = new MutableInteger();

        Tests.executeUntil(
            () -> fragmentsRead.value > numFramesToExpect,
            (j) ->
            {
                final int fragments = subscription.poll(fragmentHandler, 10);
                if (0 == fragments)
                {
                    Thread.yield();
                }
                fragmentsRead.value += fragments;
            },
            Integer.MAX_VALUE,
            TimeUnit.MILLISECONDS.toNanos(500));

        verify(fragmentHandler, times(numFramesToExpect)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(frameLength),
            any(Header.class));
    }

    @ParameterizedTest
    @MethodSource("channels")
    @InterruptAfter(10)
    void shouldNoticeDroppedSubscriber(final String channel)
    {
        launch(channel);

        Tests.awaitConnected(publication);

        subscription.close();

        while (publication.isConnected())
        {
            Tests.yield();
        }
    }


    @Test
    void shouldAllowSubscriptionsIfUsingTagsAndParametersAndAllMatch()
    {
        launch("aeron:ipc");

        final String channel = "aeron:udp?endpoint=127.0.0.1:0|control=127.0.0.1:9999|tags=1001";
        try (
            Subscription ignore1 = subscribingClient.addSubscription(channel, 1000);
            Subscription ignore2 = subscribingClient.addSubscription(channel, 1000))
        {
            Objects.requireNonNull(ignore1);
            Objects.requireNonNull(ignore2);
        }
    }

    @Test
    void shouldRejectSubscriptionsIfUsingTagsAndParametersAndEndpointDoesNotMatchEndpointWithExplicitControl()
    {
        watcher.ignoreErrorsMatching((s) -> s.contains("has explicit endpoint or control"));
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:0|control=127.0.0.1:9999|tags=1001");

        try (Subscription ignore1 = subscribingClient.addSubscription(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.endpoint("127.0.0.1:9999").build(), 1000));
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.endpoint("127.0.0.2:0").build(), 1000));
        }
    }

    @Test
    void shouldRejectSubscriptionsIfUsingTagsAndParametersAndEndpointDoesNotMatchEndpointWithoutControl()
    {
        watcher.ignoreErrorsMatching((s) -> s.contains("has explicit endpoint or control"));
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:9999|tags=1001");

        try (Subscription ignore1 = subscribingClient.addSubscription(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.endpoint("127.0.0.1:0").build(), 1000));
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.endpoint("127.0.0.2:9999").build(), 1000));
        }
    }

    @Test
    void shouldRejectSubscriptionsIfUsingTagsAndParametersAndEndpointDoesNotMatchControl()
    {
        watcher.ignoreErrorsMatching((s) -> s.contains("has explicit endpoint or control"));
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:0|control=127.0.0.1:9999|tags=1001");

        try (Subscription ignore1 = subscribingClient.addSubscription(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.controlEndpoint("127.0.0.1:10000").build(), 1000));
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.controlEndpoint("127.0.0.2:9999").build(), 1000));
        }
    }

    @Test
    void shouldRejectSubscriptionsIfUsingTagsAndParametersAndEndpointDoesNotMatchSocketReceiveBufferLength()
    {
        watcher.ignoreErrorsMatching((s) -> true);
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:0|control=127.0.0.1:9999|so-rcvbuf=128K|tags=1001");

        try (Subscription ignore1 = subscribingClient.addSubscription(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.socketRcvbufLength(64 * 1024).build(), 1000));
        }
    }

    @Test
    void shouldAllowPublicationsIfUsingTagsAndParametersAndAllMatch()
    {
        launch("aeron:ipc");

        final String channel = "aeron:udp?endpoint=127.0.0.1:9999|control=127.0.0.1:0|tags=1001";
        try (
            Publication ignore1 = subscribingClient.addPublication(channel, 1000);
            Publication ignore2 = subscribingClient.addPublication(channel, 1000))
        {
            Objects.requireNonNull(ignore1);
            Objects.requireNonNull(ignore2);
        }
    }

    @Test
    void shouldRejectPublicationsIfUsingTagsAndParametersAndEndpointDoesNotMatchEndpointWithExplicitControl()
    {
        watcher.ignoreErrorsMatching((s) -> s.contains("has explicit endpoint or control"));
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:10000|control=127.0.0.1:0|tags=1001");

        try (Publication ignore1 = subscribingClient.addPublication(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addPublication(builder.endpoint("127.0.0.1:9999").build(), 1000));
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addPublication(builder.endpoint("127.0.0.2:10000").build(), 1000));
        }
    }

    @Test
    void shouldRejectPublicationsIfUsingTagsAndParametersAndEndpointDoesNotMatchEndpointWithoutControl()
    {
        watcher.ignoreErrorsMatching((s) -> s.contains("has explicit endpoint or control"));
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:10000|tags=1001");

        try (Subscription ignore1 = subscribingClient.addSubscription(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.endpoint("127.0.0.1:9999").build(), 1000));
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addSubscription(builder.endpoint("127.0.0.2:10000").build(), 1000));
        }
    }

    @Test
    void shouldRejectPublicationsIfUsingTagsAndParametersAndEndpointDoesNotMatchControl()
    {
        watcher.ignoreErrorsMatching((s) -> s.contains("has explicit endpoint or control"));
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:10000|control=127.0.0.1:0|tags=1001");

        try (Publication ignore1 = subscribingClient.addPublication(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addPublication(builder.controlEndpoint("127.0.0.1:10000").build(), 1000));
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addPublication(builder.controlEndpoint("127.0.0.2:0").build(), 1000));
        }
    }

    @Test
    void shouldRejectPublicationsIfUsingTagsAndParametersAndMtuDoesNotMatch()
    {
        watcher.ignoreErrorsMatching((s) -> true);
        launch("aeron:ipc");

        final ChannelUriStringBuilder builder = new ChannelUriStringBuilder(
            "aeron:udp?endpoint=127.0.0.1:10000|control=127.0.0.1:0|mtu=1408|tags=1001");

        try (Publication ignore1 = subscribingClient.addPublication(builder.build(), 1000))
        {
            Objects.requireNonNull(ignore1);
            assertThrows(
                RegistrationException.class,
                () -> subscribingClient.addPublication(builder.mtu(8192).build(), 1000));
        }
    }

    private void publishMessage()
    {
        buffer.putInt(0, 1);

        while (publication.offer(buffer, 0, SIZE_OF_INT) < 0L)
        {
            Tests.yield();
        }
    }

    private void pollForFragment()
    {
        while (true)
        {
            final int fragments = subscription.poll(fragmentHandler, 10);
            if (fragments > 0)
            {
                break;
            }

            Tests.yield();
        }
    }

    private void pollForBatch(final int batchSize)
    {
        long fragmentsRead = 0;

        while (true)
        {
            final int fragments = subscription.poll(fragmentHandler, 10);
            fragmentsRead += fragments;

            if (fragmentsRead >= batchSize)
            {
                break;
            }

            if (0 == fragments)
            {
                Tests.yield();
            }
        }
    }
}
