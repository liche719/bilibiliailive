package com.bilibili.ailive.conversation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomReplySchedulerTest {

    private final ExecutorService workerExecutor = Executors.newFixedThreadPool(2);
    private final RoomReplyScheduler scheduler = new RoomReplyScheduler(1, Duration.ofSeconds(2), workerExecutor);

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void preservesOrderWithinARoomAndRejectsBeyondItsQueueCapacity() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> scheduler.execute("room-1", () -> {
            executionOrder.add("first");
            firstStarted.countDown();
            await(releaseFirst);
            return "first";
        }));
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> scheduler.execute("room-1", () -> {
            executionOrder.add("second");
            return "second";
        }));
        awaitPendingTasks("room-1", 1);

        assertThrows(RoomQueueFullException.class, () -> scheduler.execute("room-1", () -> "third"));

        releaseFirst.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertEquals("second", second.get(2, TimeUnit.SECONDS));
        assertEquals(List.of("first", "second"), executionOrder);
    }

    @Test
    void allowsDifferentRoomsToRunConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> firstRoom = CompletableFuture.supplyAsync(
                () -> scheduler.execute("room-1", () -> blockingTask("room-1", bothStarted, release))
        );
        CompletableFuture<String> secondRoom = CompletableFuture.supplyAsync(
                () -> scheduler.execute("room-2", () -> blockingTask("room-2", bothStarted, release))
        );

        assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
        release.countDown();

        assertEquals("room-1", firstRoom.get(2, TimeUnit.SECONDS));
        assertEquals("room-2", secondRoom.get(2, TimeUnit.SECONDS));
    }

    @Test
    void generatesConcurrentlyButCommitsInMessageOrder() throws Exception {
        ExecutorService concurrentWorkers = Executors.newFixedThreadPool(2);
        RoomReplyScheduler concurrentScheduler = new RoomReplyScheduler(
                2, 2, Duration.ofSeconds(2), concurrentWorkers
        );
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> commits = Collections.synchronizedList(new ArrayList<>());
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> concurrentScheduler.executeOrdered(
                    "room-1",
                    () -> {
                        bothStarted.countDown();
                        await(releaseFirst);
                        return "first";
                    },
                    value -> {
                        commits.add(value);
                        return value;
                    }
            ));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> concurrentScheduler.executeOrdered(
                    "room-1",
                    () -> {
                        bothStarted.countDown();
                        return "second";
                    },
                    value -> {
                        commits.add(value);
                        return value;
                    }
            ));

            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            assertTrue(commits.isEmpty());
            releaseFirst.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), commits);
        } finally {
            releaseFirst.countDown();
            concurrentScheduler.shutdown();
        }
    }

    @Test
    void expiresWorkThatWaitsTooLongForAWorker() throws Exception {
        ExecutorService singleWorker = Executors.newSingleThreadExecutor();
        RoomReplyScheduler shortLivedScheduler = new RoomReplyScheduler(
                1,
                Duration.ofMillis(30),
                singleWorker
        );
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(
                    () -> shortLivedScheduler.execute("room-1", () -> blockingTask("first", firstStarted, releaseFirst))
            );
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<String> expired = CompletableFuture.supplyAsync(
                    () -> shortLivedScheduler.execute("room-2", () -> "late")
            );

            Thread.sleep(60);
            releaseFirst.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> expired.get(2, TimeUnit.SECONDS)
            );
            assertTrue(exception.getCause() instanceof RoomQueueExpiredException);
        } finally {
            releaseFirst.countDown();
            shortLivedScheduler.shutdown();
        }
    }

    @Test
    void rejectsNewRoomsWhenTheGlobalSchedulerIsFull() throws Exception {
        RoomReplyScheduler globallyBoundedScheduler = new RoomReplyScheduler(
                policyWithOneScheduledRoom(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(
                    () -> globallyBoundedScheduler.execute(
                            "room-1",
                            () -> blockingTask("first", firstStarted, releaseFirst)
                    )
            );
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(
                    () -> globallyBoundedScheduler.execute("room-2", () -> "second")
            );
            awaitScheduledRooms(globallyBoundedScheduler, 2);

            assertThrows(
                    RoomQueueFullException.class,
                    () -> globallyBoundedScheduler.execute("room-3", () -> "third")
            );

            releaseFirst.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            globallyBoundedScheduler.shutdown();
        }
    }

    private void awaitPendingTasks(String roomKey, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (scheduler.pendingTasks(roomKey) != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, scheduler.pendingTasks(roomKey));
    }

    private static void awaitScheduledRooms(RoomReplyScheduler target, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (target.scheduledRooms() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, target.scheduledRooms());
    }

    private static LiveReplyPolicyProperties policyWithOneScheduledRoom() {
        return new LiveReplyPolicyProperties(
                160,
                Duration.ofSeconds(3),
                20,
                Duration.ofMinutes(1),
                1,
                1,
                1,
                Duration.ofSeconds(2),
                1,
                "test:reply-policy"
        );
    }

    private static String blockingTask(String result, CountDownLatch started, CountDownLatch release) {
        started.countDown();
        await(release);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test coordination was interrupted", exception);
        }
    }
}
