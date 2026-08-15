package com.bilibili.ailive.conversation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class RoomReplyScheduler {

    private final int maxPendingPerRoom;
    private final int maxConcurrentPerRoom;
    private final long maxQueueWaitNanos;
    private final ExecutorService executor;
    private final Counter roomCapacityDrops;
    private final Counter globalCapacityDrops;
    private final Counter expiredDrops;
    private final Timer queueWaitTimer;
    private final Map<String, RoomQueue> queuesByRoom = new HashMap<>();

    @Autowired
    RoomReplyScheduler(LiveReplyPolicyProperties properties, MeterRegistry meterRegistry) {
        this(
                properties.maxPendingPerRoom(),
                properties.maxConcurrentPerRoom(),
                properties.maxQueueWait(),
                new ThreadPoolExecutor(
                        properties.workerThreads(),
                        properties.workerThreads(),
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(properties.maxScheduledRooms()),
                        Thread.ofPlatform().name("ai-reply-", 0).factory()
                ),
                meterRegistry
        );
    }

    RoomReplyScheduler(int maxPendingPerRoom, Duration maxQueueWait, ExecutorService executor) {
        this(maxPendingPerRoom, 1, maxQueueWait, executor, new SimpleMeterRegistry());
    }

    RoomReplyScheduler(
            int maxPendingPerRoom,
            int maxConcurrentPerRoom,
            Duration maxQueueWait,
            ExecutorService executor
    ) {
        this(maxPendingPerRoom, maxConcurrentPerRoom, maxQueueWait, executor, new SimpleMeterRegistry());
    }

    private RoomReplyScheduler(
            int maxPendingPerRoom,
            int maxConcurrentPerRoom,
            Duration maxQueueWait,
            ExecutorService executor,
            MeterRegistry meterRegistry
    ) {
        if (maxPendingPerRoom < 1
                || maxConcurrentPerRoom < 1
                || maxQueueWait == null
                || maxQueueWait.isNegative()
                || maxQueueWait.isZero()) {
            throw new IllegalArgumentException("Room reply queue limits must be positive");
        }
        this.maxPendingPerRoom = maxPendingPerRoom;
        this.maxConcurrentPerRoom = maxConcurrentPerRoom;
        this.maxQueueWaitNanos = maxQueueWait.toNanos();
        this.executor = executor;
        this.roomCapacityDrops = meterRegistry.counter("ai.live.reply.queue.dropped", "reason", "room_capacity");
        this.globalCapacityDrops = meterRegistry.counter("ai.live.reply.queue.dropped", "reason", "global_capacity");
        this.expiredDrops = meterRegistry.counter("ai.live.reply.queue.dropped", "reason", "expired");
        this.queueWaitTimer = Timer.builder("ai.live.reply.queue.wait")
                .description("Time spent waiting before reply generation starts")
                .register(meterRegistry);
        Gauge.builder("ai.live.reply.queue.active.rooms", this, RoomReplyScheduler::scheduledRooms)
                .register(meterRegistry);
        Gauge.builder("ai.live.reply.queue.pending", this, RoomReplyScheduler::pendingMessages)
                .register(meterRegistry);
    }

    <T> T execute(String roomKey, Supplier<T> task) {
        return executeOrdered(roomKey, task, Function.identity());
    }

    <T, R> R executeOrdered(String roomKey, Supplier<T> generation, Function<T, R> orderedCommit) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        QueuedTask task;
        synchronized (this) {
            RoomQueue queue = queuesByRoom.computeIfAbsent(roomKey, ignored -> new RoomQueue());
            if (queue.active >= maxConcurrentPerRoom && queue.pending.size() >= maxPendingPerRoom) {
                roomCapacityDrops.increment();
                cleanupIfIdle(roomKey, queue);
                throw new RoomQueueFullException();
            }
            long sequence = queue.nextSequence++;
            task = new QueuedTask(
                    sequence,
                    System.nanoTime(),
                    generation,
                    value -> orderedCommit.apply(cast(value)),
                    result
            );
            if (queue.active < maxConcurrentPerRoom) {
                if (!start(roomKey, queue, task)) {
                    cleanupIfIdle(roomKey, queue);
                    throw new RoomQueueFullException();
                }
            } else {
                queue.pending.addLast(task);
            }
        }
        try {
            return cast(result.join());
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private boolean start(String roomKey, RoomQueue queue, QueuedTask task) {
        queue.active++;
        try {
            executor.execute(() -> generate(roomKey, queue, task));
            return true;
        } catch (RejectedExecutionException exception) {
            queue.active--;
            globalCapacityDrops.increment();
            return false;
        }
    }

    private void generate(String roomKey, RoomQueue queue, QueuedTask task) {
        long queueWait = System.nanoTime() - task.enqueuedAtNanos();
        Outcome outcome;
        if (queueWait > maxQueueWaitNanos) {
            expiredDrops.increment();
            outcome = Outcome.failure(new RoomQueueExpiredException());
        } else {
            queueWaitTimer.record(Duration.ofNanos(queueWait));
            try {
                outcome = Outcome.success(task.generation().get());
            } catch (Throwable failure) {
                outcome = Outcome.failure(failure);
            }
        }
        generationFinished(roomKey, queue, task, outcome);
    }

    private void generationFinished(String roomKey, RoomQueue queue, QueuedTask task, Outcome outcome) {
        boolean drainHere = false;
        synchronized (this) {
            queue.active--;
            queue.completed.put(task.sequence(), new CompletedTask(task, outcome));
            startWaitingTasks(roomKey, queue);
            if (!queue.committing) {
                queue.committing = true;
                drainHere = true;
            }
        }
        if (drainHere) {
            drainCompleted(roomKey, queue);
        }
    }

    private void startWaitingTasks(String roomKey, RoomQueue queue) {
        while (queue.active < maxConcurrentPerRoom && !queue.pending.isEmpty()) {
            QueuedTask next = queue.pending.removeFirst();
            if (start(roomKey, queue, next)) {
                continue;
            }
            queue.completed.put(next.sequence(), new CompletedTask(next, Outcome.failure(new RoomQueueFullException())));
        }
    }

    private void drainCompleted(String roomKey, RoomQueue queue) {
        while (true) {
            CompletedTask completed;
            synchronized (this) {
                completed = queue.completed.remove(queue.nextCommitSequence);
                if (completed == null) {
                    queue.committing = false;
                    cleanupIfIdle(roomKey, queue);
                    return;
                }
                queue.nextCommitSequence++;
            }
            completed.commit();
        }
    }

    private void cleanupIfIdle(String roomKey, RoomQueue queue) {
        if (queue.active == 0 && queue.pending.isEmpty() && queue.completed.isEmpty() && !queue.committing) {
            queuesByRoom.remove(roomKey, queue);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    synchronized int pendingTasks(String roomKey) {
        RoomQueue queue = queuesByRoom.get(roomKey);
        return queue == null ? 0 : queue.pending.size();
    }

    synchronized int activeTasks(String roomKey) {
        RoomQueue queue = queuesByRoom.get(roomKey);
        return queue == null ? 0 : queue.active;
    }

    synchronized int scheduledRooms() {
        return queuesByRoom.size();
    }

    synchronized int pendingMessages() {
        return queuesByRoom.values().stream().mapToInt(queue -> queue.pending.size()).sum();
    }

    public synchronized ReplyQueueSnapshot snapshot() {
        return new ReplyQueueSnapshot(scheduledRooms(), pendingMessages());
    }

    public synchronized boolean isBusy(String roomKey) {
        RoomQueue queue = queuesByRoom.get(roomKey);
        return queue != null && (queue.active > 0
                || !queue.pending.isEmpty()
                || !queue.completed.isEmpty()
                || queue.committing);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static final class RoomQueue {
        private final ArrayDeque<QueuedTask> pending = new ArrayDeque<>();
        private final TreeMap<Long, CompletedTask> completed = new TreeMap<>();
        private int active;
        private long nextSequence;
        private long nextCommitSequence;
        private boolean committing;
    }

    private record QueuedTask(
            long sequence,
            long enqueuedAtNanos,
            Supplier<?> generation,
            Function<Object, Object> orderedCommit,
            CompletableFuture<Object> result
    ) {
    }

    private record Outcome(Object value, Throwable failure) {
        static Outcome success(Object value) {
            return new Outcome(value, null);
        }

        static Outcome failure(Throwable failure) {
            return new Outcome(null, failure);
        }
    }

    private record CompletedTask(QueuedTask task, Outcome outcome) {
        void commit() {
            if (outcome.failure() != null) {
                task.result().completeExceptionally(outcome.failure());
                return;
            }
            try {
                task.result().complete(task.orderedCommit().apply(outcome.value()));
            } catch (Throwable failure) {
                task.result().completeExceptionally(failure);
            }
        }
    }
}
