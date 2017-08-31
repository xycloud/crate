/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.metadata;

import com.google.common.base.Splitter;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Class which unpacks and holds the different entities of a CrateDB index name.
 */
public class IndexParts {

    // Index names are only allowed to contain '.' as separators
    private static final Splitter SPLITTER = Splitter.on(".").limit(6);
    private static final String PARTITIONED_KEY_WORD = "partitioned";
    private static final String PARTITIONED_TABLE_PREFIX = "." + PARTITIONED_KEY_WORD;

    private final String schema;
    private final String table;
    private final String partitionIdent;

    public IndexParts(String indexName) {
        List<String> parts = SPLITTER.splitToList(indexName);
        switch (parts.size()) {
            case 1:
                // "table_name"
                schema = Schemas.DOC_SCHEMA_NAME;
                table = indexName;
                partitionIdent = null;
                break;
            case 2:
                // "schema"."table_name"
                schema = parts.get(0);
                table = parts.get(1);
                partitionIdent = null;
                break;
            case 4:
                // ""."partitioned"."table_name". ["ident"]
                assertEmpty(parts.get(0));
                schema = Schemas.DOC_SCHEMA_NAME;
                assertPartitionPrefix(parts.get(1));
                table = parts.get(2);
                partitionIdent = parts.get(3);
                break;
            case 5:
                // "schema".""."partitioned"."table_name". ["ident"]
                schema = parts.get(0);
                assertEmpty(parts.get(1));
                assertPartitionPrefix(parts.get(2));
                table = parts.get(3);
                partitionIdent = parts.get(4);
                break;
            default:
                throw new IllegalArgumentException("Invalid index name: " + indexName);
        }
    }

    public String getSchema() {
        return schema;
    }

    public String getTable() {
        return table;
    }

    public String getPartitionIdent() {
        return isPartitioned() ? partitionIdent : "";
    }

    public boolean isPartitioned() {
        return partitionIdent != null;
    }

    public TableIdent toTableIdent() {
        return new TableIdent(schema, table);
    }

    public PartitionName toPartitionName() {
        return new PartitionName(schema, table, partitionIdent);
    }

    public String toFullyQualifiedName() {
        return schema + "." + table;
    }

    public boolean matchesSchema(String schema) {
        return this.schema.equals(schema);
    }

    /**
     * Encodes the index parts to a CrateDB index name.
     */
    public String toIndexName() {
        return toIndexName(schema, table, partitionIdent);
    }

    public static String toIndexName(TableIdent tableIdent, String partitionIdent) {
        return toIndexName(tableIdent.schema(), tableIdent.name(), partitionIdent);
    }

    public static String toIndexName(PartitionName partitionName) {
        TableIdent tableIdent = partitionName.tableIdent();
        return toIndexName(tableIdent.schema(), tableIdent.name(), partitionName.ident());
    }

    /**
     * Encodes the index parts to a CrateDB index name.
     */
    public static String toIndexName(String schema, String table, @Nullable String partitionIdent) {
        StringBuilder stringBuilder = new StringBuilder();
        final boolean isPartitioned = partitionIdent != null;
        if (!schema.equalsIgnoreCase(Schemas.DOC_SCHEMA_NAME)) {
            stringBuilder.
                append(schema).append(".");
        }
        if (isPartitioned) {
            stringBuilder
                .append(PARTITIONED_TABLE_PREFIX).append(".");
        }
        stringBuilder.append(table);
        if (isPartitioned) {
            stringBuilder.append(".").append(partitionIdent);
        }
        return stringBuilder.toString();
    }

    public static boolean isPartitioned(String index) {
        return new IndexParts(index).isPartitioned();
    }

    private static void assertPartitionPrefix(String prefix) {
        if (!PARTITIONED_KEY_WORD.equals(prefix)) {
            throw new IllegalArgumentException("Invalid partition prefix: " + prefix);
        }
    }

    private static void assertEmpty(String prefix) {
        if (!"".equals(prefix)) {
            throw new IllegalArgumentException("Invalid index name: " + prefix);
        }
    }
}
