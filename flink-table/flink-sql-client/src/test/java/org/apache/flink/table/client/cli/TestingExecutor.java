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

package org.apache.flink.table.client.cli;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.api.internal.TableResultInternal;
import org.apache.flink.table.client.cli.utils.SqlParserHelper;
import org.apache.flink.table.client.gateway.Executor;
import org.apache.flink.table.client.gateway.ResultDescriptor;
import org.apache.flink.table.client.gateway.SqlExecutionException;
import org.apache.flink.table.client.gateway.TypedResult;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.QueryOperation;
import org.apache.flink.util.function.SupplierWithException;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A customizable {@link Executor} for testing purposes. */
class TestingExecutor implements Executor {

    private static final Configuration defaultConfig = new Configuration();
    private int numCancelCalls = 0;

    private int numRetrieveResultChancesCalls = 0;
    private final List<SupplierWithException<TypedResult<List<RowData>>, SqlExecutionException>>
            resultChanges;

    private int numRetrieveResultPageCalls = 0;
    private final List<SupplierWithException<List<RowData>, SqlExecutionException>> resultPages;

    private final SqlParserHelper helper;

    TestingExecutor(
            List<SupplierWithException<TypedResult<List<RowData>>, SqlExecutionException>>
                    resultChanges,
            List<SupplierWithException<List<RowData>, SqlExecutionException>> resultPages) {
        this.resultChanges = resultChanges;
        this.resultPages = resultPages;
        helper = new SqlParserHelper();
        helper.registerTables();
    }

    @Override
    public void cancelQuery(String sessionId, String resultId) throws SqlExecutionException {
        numCancelCalls++;
    }

    @Override
    public void removeJar(String sessionId, String jarUrl) {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public Optional<String> stopJob(
            String sessionId, String jobId, boolean isWithSavepoint, boolean isWithDrain)
            throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public TypedResult<List<RowData>> retrieveResultChanges(String sessionId, String resultId)
            throws SqlExecutionException {
        return resultChanges
                .get(Math.min(numRetrieveResultChancesCalls++, resultChanges.size() - 1))
                .get();
    }

    @Override
    public List<RowData> retrieveResultPage(String resultId, int page)
            throws SqlExecutionException {
        return resultPages
                .get(Math.min(numRetrieveResultPageCalls++, resultPages.size() - 1))
                .get();
    }

    @Override
    public TypedResult<Integer> snapshotResult(String sessionId, String resultId, int pageSize)
            throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public void start() throws SqlExecutionException {}

    @Override
    public String openSession(@Nullable String sessionId) throws SqlExecutionException {
        return sessionId;
    }

    @Override
    public void closeSession(String sessionId) throws SqlExecutionException {}

    @Override
    public Map<String, String> getSessionConfigMap(String sessionId) throws SqlExecutionException {
        return defaultConfig.toMap();
    }

    @Override
    public ReadableConfig getSessionConfig(String sessionId) throws SqlExecutionException {
        return defaultConfig;
    }

    @Override
    public void resetSessionProperties(String sessionId) throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public void resetSessionProperty(String sessionId, String key) throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public void setSessionProperty(String sessionId, String key, String value)
            throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public List<String> completeStatement(String sessionId, String statement, int position) {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public TableResultInternal executeOperation(String sessionId, Operation operation)
            throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public TableResultInternal executeModifyOperations(
            String sessionId, List<ModifyOperation> operations) throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public Operation parseStatement(String sessionId, String statement)
            throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override
    public ResultDescriptor executeQuery(String sessionId, QueryOperation query)
            throws SqlExecutionException {
        throw new UnsupportedOperationException("Not implemented.");
    }

    public int getNumCancelCalls() {
        return numCancelCalls;
    }

    public int getNumRetrieveResultChancesCalls() {
        return numRetrieveResultChancesCalls;
    }

    public int getNumRetrieveResultPageCalls() {
        return numRetrieveResultPageCalls;
    }
}
