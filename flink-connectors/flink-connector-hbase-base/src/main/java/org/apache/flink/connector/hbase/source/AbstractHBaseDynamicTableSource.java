/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.hbase.source;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.connector.hbase.util.HBaseTableSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.Projection;
import org.apache.flink.table.connector.source.InputFormatProvider;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.connector.source.lookup.LookupFunctionProvider;
import org.apache.flink.table.connector.source.lookup.PartialCachingLookupProvider;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.DataType;

import org.apache.hadoop.conf.Configuration;

import javax.annotation.Nullable;

import static org.apache.flink.util.Preconditions.checkArgument;

/** HBase table source implementation. */
@Internal
public abstract class AbstractHBaseDynamicTableSource
        implements ScanTableSource, LookupTableSource, SupportsProjectionPushDown {

    protected final Configuration conf;
    protected final String tableName;
    protected HBaseTableSchema hbaseSchema;
    protected final String nullStringLiteral;
    protected final int maxRetryTimes;
    @Nullable protected final LookupCache cache;

    public AbstractHBaseDynamicTableSource(
            Configuration conf,
            String tableName,
            HBaseTableSchema hbaseSchema,
            String nullStringLiteral,
            int maxRetryTimes,
            @Nullable LookupCache cache) {
        this.conf = conf;
        this.tableName = tableName;
        this.hbaseSchema = hbaseSchema;
        this.nullStringLiteral = nullStringLiteral;
        this.maxRetryTimes = maxRetryTimes;
        this.cache = cache;
    }

    @Override
    public ScanRuntimeProvider getScanRuntimeProvider(ScanContext runtimeProviderContext) {
        return InputFormatProvider.of(getInputFormat());
    }

    protected abstract InputFormat<RowData, ?> getInputFormat();

    @Override
    public LookupRuntimeProvider getLookupRuntimeProvider(LookupContext context) {
        checkArgument(
                context.getKeys().length == 1 && context.getKeys()[0].length == 1,
                "Currently, HBase table can only be lookup by single rowkey.");
        checkArgument(
                hbaseSchema.getRowKeyName().isPresent(),
                "HBase schema must have a row key when used in lookup mode.");
        checkArgument(
                DataType.getFieldNames(hbaseSchema.convertToDataType())
                        .get(context.getKeys()[0][0])
                        .equals(hbaseSchema.getRowKeyName().get()),
                "Currently, HBase table only supports lookup by rowkey field.");
        HBaseRowDataLookupFunction lookupFunction =
                new HBaseRowDataLookupFunction(
                        conf, tableName, hbaseSchema, nullStringLiteral, maxRetryTimes);
        if (cache != null) {
            return PartialCachingLookupProvider.of(lookupFunction, cache);
        } else {
            return LookupFunctionProvider.of(lookupFunction);
        }
    }

    @Override
    public boolean supportsNestedProjection() {
        // planner doesn't support nested projection push down yet.
        return false;
    }

    @Override
    public void applyProjection(int[][] projectedFields, DataType producedDataType) {
        this.hbaseSchema =
                HBaseTableSchema.fromDataType(
                        Projection.of(projectedFields).project(hbaseSchema.convertToDataType()));
    }

    @Override
    public ChangelogMode getChangelogMode() {
        return ChangelogMode.insertOnly();
    }

    @Override
    public String asSummaryString() {
        return "HBase";
    }

    // -------------------------------------------------------------------------------------------

    @VisibleForTesting
    public HBaseTableSchema getHBaseTableSchema() {
        return this.hbaseSchema;
    }

    @VisibleForTesting
    public int getMaxRetryTimes() {
        return maxRetryTimes;
    }

    @VisibleForTesting
    @Nullable
    public LookupCache getCache() {
        return cache;
    }
}
