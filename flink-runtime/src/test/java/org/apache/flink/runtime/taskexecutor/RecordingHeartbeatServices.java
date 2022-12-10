/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.taskexecutor;

import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.heartbeat.HeartbeatListener;
import org.apache.flink.runtime.heartbeat.HeartbeatManager;
import org.apache.flink.runtime.heartbeat.HeartbeatManagerImpl;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.heartbeat.HeartbeatTarget;
import org.apache.flink.util.concurrent.ScheduledExecutor;

import org.slf4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Special {@link HeartbeatServices} which creates a {@link RecordingHeartbeatManagerImpl}. */
final class RecordingHeartbeatServices extends HeartbeatServices {

    private final BlockingQueue<ResourceID> unmonitoredTargets;

    private final BlockingQueue<ResourceID> monitoredTargets;

    public RecordingHeartbeatServices(long heartbeatInterval, long heartbeatTimeout) {
        super(heartbeatInterval, heartbeatTimeout);

        this.unmonitoredTargets = new ArrayBlockingQueue<>(1);
        this.monitoredTargets = new ArrayBlockingQueue<>(1);
    }

    @Override
    public <I, O> HeartbeatManager<I, O> createHeartbeatManager(
            ResourceID resourceId,
            HeartbeatListener<I, O> heartbeatListener,
            ScheduledExecutor mainThreadExecutor,
            Logger log) {
        return new RecordingHeartbeatManagerImpl<>(
                heartbeatTimeout,
                failedRpcRequestsUntilUnreachable,
                resourceId,
                heartbeatListener,
                mainThreadExecutor,
                log,
                unmonitoredTargets,
                monitoredTargets);
    }

    public BlockingQueue<ResourceID> getUnmonitoredTargets() {
        return unmonitoredTargets;
    }

    public BlockingQueue<ResourceID> getMonitoredTargets() {
        return monitoredTargets;
    }

    /** {@link HeartbeatManagerImpl} which records the unmonitored targets. */
    private static final class RecordingHeartbeatManagerImpl<I, O>
            extends HeartbeatManagerImpl<I, O> {

        private final BlockingQueue<ResourceID> unmonitoredTargets;

        private final BlockingQueue<ResourceID> monitoredTargets;

        public RecordingHeartbeatManagerImpl(
                long heartbeatTimeoutIntervalMs,
                int failedRpcRequestsUntilUnreachable,
                ResourceID ownResourceID,
                HeartbeatListener<I, O> heartbeatListener,
                ScheduledExecutor mainThreadExecutor,
                Logger log,
                BlockingQueue<ResourceID> unmonitoredTargets,
                BlockingQueue<ResourceID> monitoredTargets) {
            super(
                    heartbeatTimeoutIntervalMs,
                    failedRpcRequestsUntilUnreachable,
                    ownResourceID,
                    heartbeatListener,
                    mainThreadExecutor,
                    log);
            this.unmonitoredTargets = unmonitoredTargets;
            this.monitoredTargets = monitoredTargets;
        }

        @Override
        public void unmonitorTarget(ResourceID resourceID) {
            super.unmonitorTarget(resourceID);
            unmonitoredTargets.offer(resourceID);
        }

        @Override
        public void monitorTarget(ResourceID resourceID, HeartbeatTarget<O> heartbeatTarget) {
            super.monitorTarget(resourceID, heartbeatTarget);
            monitoredTargets.offer(resourceID);
        }
    }
}
