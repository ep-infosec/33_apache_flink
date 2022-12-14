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

package org.apache.flink.runtime.checkpoint.channel;

import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter.ChannelStateWriteResult;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.ThrowingConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.apache.flink.runtime.checkpoint.channel.CheckpointInProgressRequestState.CANCELLED;
import static org.apache.flink.runtime.checkpoint.channel.CheckpointInProgressRequestState.COMPLETED;
import static org.apache.flink.runtime.checkpoint.channel.CheckpointInProgressRequestState.EXECUTING;
import static org.apache.flink.runtime.checkpoint.channel.CheckpointInProgressRequestState.FAILED;
import static org.apache.flink.runtime.checkpoint.channel.CheckpointInProgressRequestState.NEW;
import static org.apache.flink.util.CloseableIterator.ofElements;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

interface ChannelStateWriteRequest {

    Logger LOG = LoggerFactory.getLogger(ChannelStateWriteRequest.class);

    long getCheckpointId();

    void cancel(Throwable cause) throws Exception;

    static CheckpointInProgressRequest completeInput(long checkpointId) {
        return new CheckpointInProgressRequest(
                "completeInput", checkpointId, ChannelStateCheckpointWriter::completeInput);
    }

    static CheckpointInProgressRequest completeOutput(long checkpointId) {
        return new CheckpointInProgressRequest(
                "completeOutput", checkpointId, ChannelStateCheckpointWriter::completeOutput);
    }

    static ChannelStateWriteRequest write(
            long checkpointId, InputChannelInfo info, CloseableIterator<Buffer> iterator) {
        return buildWriteRequest(
                checkpointId,
                "writeInput",
                iterator,
                (writer, buffer) -> writer.writeInput(info, buffer));
    }

    static ChannelStateWriteRequest write(
            long checkpointId, ResultSubpartitionInfo info, Buffer... buffers) {
        return buildWriteRequest(
                checkpointId,
                "writeOutput",
                ofElements(Buffer::recycleBuffer, buffers),
                (writer, buffer) -> writer.writeOutput(info, buffer));
    }

    static ChannelStateWriteRequest write(
            long checkpointId,
            ResultSubpartitionInfo info,
            CompletableFuture<List<Buffer>> dataFuture) {
        return buildFutureWriteRequest(
                checkpointId,
                "writeOutputFuture",
                dataFuture,
                (writer, buffer) -> writer.writeOutput(info, buffer));
    }

    static ChannelStateWriteRequest buildFutureWriteRequest(
            long checkpointId,
            String name,
            CompletableFuture<List<Buffer>> dataFuture,
            BiConsumer<ChannelStateCheckpointWriter, Buffer> bufferConsumer) {
        return new CheckpointInProgressRequest(
                name,
                checkpointId,
                writer -> {
                    List<Buffer> buffers;
                    try {
                        buffers = dataFuture.get();
                    } catch (ExecutionException e) {
                        // If dataFuture fails, fail only the single related writer
                        writer.fail(e);
                        return;
                    }
                    for (Buffer buffer : buffers) {
                        checkBufferIsBuffer(buffer);
                        bufferConsumer.accept(writer, buffer);
                    }
                },
                throwable ->
                        dataFuture.thenAccept(
                                buffers -> {
                                    try {
                                        CloseableIterator.fromList(buffers, Buffer::recycleBuffer)
                                                .close();
                                    } catch (Exception e) {
                                        LOG.error(
                                                "Failed to recycle the output buffer of channel state.",
                                                e);
                                    }
                                }));
    }

    static ChannelStateWriteRequest buildWriteRequest(
            long checkpointId,
            String name,
            CloseableIterator<Buffer> iterator,
            BiConsumer<ChannelStateCheckpointWriter, Buffer> bufferConsumer) {
        return new CheckpointInProgressRequest(
                name,
                checkpointId,
                writer -> {
                    while (iterator.hasNext()) {
                        Buffer buffer = iterator.next();
                        checkBufferIsBuffer(buffer);
                        bufferConsumer.accept(writer, buffer);
                    }
                },
                throwable -> iterator.close());
    }

    static void checkBufferIsBuffer(Buffer buffer) {
        try {
            checkArgument(buffer.isBuffer());
        } catch (Exception e) {
            buffer.recycleBuffer();
            throw e;
        }
    }

    static ChannelStateWriteRequest start(
            long checkpointId,
            ChannelStateWriteResult targetResult,
            CheckpointStorageLocationReference locationReference) {
        return new CheckpointStartRequest(checkpointId, targetResult, locationReference);
    }

    static ChannelStateWriteRequest abort(long checkpointId, Throwable cause) {
        return new CheckpointAbortRequest(checkpointId, cause);
    }

    static ThrowingConsumer<Throwable, Exception> recycle(Buffer[] flinkBuffers) {
        return unused -> {
            for (Buffer b : flinkBuffers) {
                b.recycleBuffer();
            }
        };
    }
}

final class CheckpointStartRequest implements ChannelStateWriteRequest {
    private final ChannelStateWriteResult targetResult;
    private final CheckpointStorageLocationReference locationReference;
    private final long checkpointId;

    CheckpointStartRequest(
            long checkpointId,
            ChannelStateWriteResult targetResult,
            CheckpointStorageLocationReference locationReference) {
        this.checkpointId = checkpointId;
        this.targetResult = checkNotNull(targetResult);
        this.locationReference = checkNotNull(locationReference);
    }

    @Override
    public long getCheckpointId() {
        return checkpointId;
    }

    ChannelStateWriteResult getTargetResult() {
        return targetResult;
    }

    public CheckpointStorageLocationReference getLocationReference() {
        return locationReference;
    }

    @Override
    public void cancel(Throwable cause) {
        targetResult.fail(cause);
    }

    @Override
    public String toString() {
        return "start " + checkpointId;
    }
}

enum CheckpointInProgressRequestState {
    NEW,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

final class CheckpointInProgressRequest implements ChannelStateWriteRequest {
    private final ThrowingConsumer<ChannelStateCheckpointWriter, Exception> action;
    private final ThrowingConsumer<Throwable, Exception> discardAction;
    private final long checkpointId;
    private final String name;
    private final AtomicReference<CheckpointInProgressRequestState> state =
            new AtomicReference<>(NEW);

    CheckpointInProgressRequest(
            String name,
            long checkpointId,
            ThrowingConsumer<ChannelStateCheckpointWriter, Exception> action) {
        this(name, checkpointId, action, unused -> {});
    }

    CheckpointInProgressRequest(
            String name,
            long checkpointId,
            ThrowingConsumer<ChannelStateCheckpointWriter, Exception> action,
            ThrowingConsumer<Throwable, Exception> discardAction) {
        this.checkpointId = checkpointId;
        this.action = checkNotNull(action);
        this.discardAction = checkNotNull(discardAction);
        this.name = checkNotNull(name);
    }

    @Override
    public long getCheckpointId() {
        return checkpointId;
    }

    @Override
    public void cancel(Throwable cause) throws Exception {
        if (state.compareAndSet(NEW, CANCELLED) || state.compareAndSet(FAILED, CANCELLED)) {
            discardAction.accept(cause);
        }
    }

    void execute(ChannelStateCheckpointWriter channelStateCheckpointWriter) throws Exception {
        Preconditions.checkState(state.compareAndSet(NEW, EXECUTING));
        try {
            action.accept(channelStateCheckpointWriter);
            state.set(COMPLETED);
        } catch (Exception e) {
            state.set(FAILED);
            throw e;
        }
    }

    @Override
    public String toString() {
        return name + " " + checkpointId;
    }
}

final class CheckpointAbortRequest implements ChannelStateWriteRequest {

    private final long checkpointId;

    private final Throwable throwable;

    public CheckpointAbortRequest(long checkpointId, Throwable throwable) {
        this.checkpointId = checkpointId;
        this.throwable = throwable;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public long getCheckpointId() {
        return checkpointId;
    }

    @Override
    public void cancel(Throwable cause) throws Exception {}

    @Override
    public String toString() {
        return "Abort checkpointId-" + checkpointId;
    }
}
