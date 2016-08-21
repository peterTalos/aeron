/*
 * Copyright 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.*;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.fail;

public class MemoryOrderingTest
{
    public static final String CHANNEL = "aeron:udp?endpoint=localhost:54325";
    public static final int STREAM_ID = 1;
    public static final int FRAGMENT_COUNT_LIMIT = 1;
    public static final int MESSAGE_LENGTH = 512;
    public static final int NUM_MESSAGES = 1_000_000;
    public static final int BURST_LENGTH = 5;
    public static final int INTER_BURST_DURATION = 20_000;

    volatile String failedMessage = null;

    @Ignore
    @Test(timeout = 10000)
    public void shouldReceiveMessagesInOrderWithFirstLongWordIntact() throws Exception
    {
        final MediaDriver.Context ctx = new MediaDriver.Context();

        try (final MediaDriver ignore = MediaDriver.launch(ctx);
             final Aeron aeron = Aeron.connect();
             final Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             final Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID))
        {
            final UnsafeBuffer srcBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MESSAGE_LENGTH));
            final BusySpinIdleStrategy idleStrategy = new BusySpinIdleStrategy();

            final Thread subscriberThread = new Thread(new Subscriber(subscription));
            subscriberThread.start();

            for (int i = 0; i < NUM_MESSAGES; i++)
            {
                if (null != failedMessage)
                {
                    fail(failedMessage);
                }

                srcBuffer.putLong(0, i);

                while (publication.offer(srcBuffer) < 0L)
                {
                    if (null != failedMessage)
                    {
                        fail(failedMessage);
                    }

                    idleStrategy.idle();
                }

                if (i % BURST_LENGTH == 0)
                {
                    final long timeout = System.nanoTime() + INTER_BURST_DURATION;
                    long now;
                    do
                    {
                        now = System.nanoTime();
                    }
                    while (now < timeout);
                }
            }

            subscriberThread.join();
        }
        finally
        {
            ctx.deleteAeronDirectory();
        }
    }

    public class Subscriber implements Runnable, FragmentHandler
    {
        private final Subscription subscription;

        long previousValue = -1;
        int messageNum = 0;

        public Subscriber(final Subscription subscription)
        {
            this.subscription = subscription;
        }

        public void run()
        {
            final BusySpinIdleStrategy idleStrategy = new BusySpinIdleStrategy();

            while (messageNum < NUM_MESSAGES && null == failedMessage)
            {
                idleStrategy.idle(subscription.poll(this, FRAGMENT_COUNT_LIMIT));
            }
        }

        public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            final long messageValue = buffer.getLong(offset);

            final long expectedValue = previousValue + 1;
            if (messageValue != expectedValue)
            {
                final long messageValueSecondRead = buffer.getLong(offset);

                final String msg = "Issue at message number transition: " + previousValue + " -> " + messageValue;

                System.out.println(msg);
                System.out.println("expected bytes: " + byteString(expectedValue));
                System.out.println("received bytes: " + byteString(messageValue));

                System.out.println("messageValue on second read: " + messageValueSecondRead);
                System.out.println("messageValue on third read: " + buffer.getLong(offset));

                failedMessage = msg;
            }

            previousValue = messageValue;
            messageNum++;
        }

        private String byteString(final long value)
        {
            return String.format("%x %x %x %x %x %x %x %x",
                (byte)(value >>> 56),
                (byte)(value >>> 48),
                (byte)(value >>> 40),
                (byte)(value >>> 32),
                (byte)(value >>> 24),
                (byte)(value >>> 18),
                (byte)(value >>> 8),
                (byte)value);
        }
    }
}
