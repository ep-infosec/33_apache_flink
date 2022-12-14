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

package org.apache.flink.connector.hbase1.source;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.connector.hbase.source.AbstractHBaseDynamicTableSource;
import org.apache.flink.connector.hbase.util.HBaseTableSchema;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.lookup.cache.LookupCache;
import org.apache.flink.table.data.RowData;

import org.apache.hadoop.conf.Configuration;

import javax.annotation.Nullable;

import java.util.Objects;

/** HBase table source implementation. */
@Internal
public class HBaseDynamicTableSource extends AbstractHBaseDynamicTableSource {

    public HBaseDynamicTableSource(
            Configuration conf,
            String tableName,
            HBaseTableSchema hbaseSchema,
            String nullStringLiteral,
            int maxRetryTimes,
            @Nullable LookupCache cache) {
        super(conf, tableName, hbaseSchema, nullStringLiteral, maxRetryTimes, cache);
    }

    @Override
    public DynamicTableSource copy() {
        return new HBaseDynamicTableSource(
                conf, tableName, hbaseSchema, nullStringLiteral, maxRetryTimes, cache);
    }

    @Override
    public InputFormat<RowData, ?> getInputFormat() {
        return new HBaseRowDataInputFormat(conf, tableName, hbaseSchema, nullStringLiteral);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HBaseDynamicTableSource)) {
            return false;
        }
        HBaseDynamicTableSource that = (HBaseDynamicTableSource) o;
        return Objects.equals(conf, that.conf)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(hbaseSchema, that.hbaseSchema)
                && Objects.equals(nullStringLiteral, that.nullStringLiteral)
                && Objects.equals(maxRetryTimes, that.maxRetryTimes)
                && Objects.equals(cache, that.cache);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conf, tableName, hbaseSchema, nullStringLiteral, maxRetryTimes, cache);
    }
}
