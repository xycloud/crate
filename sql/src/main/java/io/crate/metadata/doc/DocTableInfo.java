/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.doc;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import io.crate.Version;
import io.crate.action.sql.SessionContext;
import io.crate.analyze.PartitionedTableParameterInfo;
import io.crate.analyze.TableParameterInfo;
import io.crate.analyze.WhereClause;
import io.crate.analyze.symbol.DynamicReference;
import io.crate.exceptions.ColumnUnknownException;
import io.crate.exceptions.UnavailableShardsException;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.GeneratedReference;
import io.crate.metadata.IndexReference;
import io.crate.metadata.PartitionName;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.Routing;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.TableIdent;
import io.crate.metadata.sys.TableColumn;
import io.crate.metadata.table.ColumnPolicy;
import io.crate.metadata.table.Operation;
import io.crate.metadata.table.ShardedTable;
import io.crate.metadata.table.StoredTable;
import io.crate.metadata.table.TableInfo;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.index.IndexNotFoundException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


/**
 * Represents a user table.
 * <p>
 *     A user table either maps to 1 lucene index (if not partitioned)
 *     Or to multiple indices (if partitioned, or an alias)
 * </p>
 */
public class DocTableInfo implements TableInfo, ShardedTable, StoredTable {

    private final List<Reference> columns;
    private final List<GeneratedReference> generatedColumns;
    private final List<Reference> partitionedByColumns;
    private final ImmutableCollection<ColumnIdent> notNullColumns;
    private final Map<ColumnIdent, IndexReference> indexColumns;
    private final ImmutableMap<ColumnIdent, Reference> references;
    private final ImmutableMap<ColumnIdent, String> analyzers;
    private final TableIdent ident;
    private final List<ColumnIdent> primaryKeys;
    private final ColumnIdent clusteredBy;
    private final String[] concreteIndices;
    private final String[] concreteOpenIndices;
    private final List<ColumnIdent> partitionedBy;
    private final int numberOfShards;
    private final BytesRef numberOfReplicas;
    private final ImmutableMap<String, Object> tableParameters;
    private final TableColumn docColumn;
    private final ClusterService clusterService;
    private final TableParameterInfo tableParameterInfo;
    private final Set<Operation> supportedOperations;

    private final List<PartitionName> partitions;

    private final boolean isAlias;
    private final boolean hasAutoGeneratedPrimaryKey;
    private final boolean isPartitioned;
    private final String routingHashFunction;
    private final Version versionCreated;
    private final Version versionUpgraded;

    private final boolean closed;

    private final ColumnPolicy columnPolicy;
    private final IndexNameExpressionResolver indexNameExpressionResolver;

    public DocTableInfo(TableIdent ident,
                        List<Reference> columns,
                        List<Reference> partitionedByColumns,
                        List<GeneratedReference> generatedColumns,
                        ImmutableCollection<ColumnIdent> notNullColumns,
                        ImmutableMap<ColumnIdent, IndexReference> indexColumns,
                        ImmutableMap<ColumnIdent, Reference> references,
                        ImmutableMap<ColumnIdent, String> analyzers,
                        List<ColumnIdent> primaryKeys,
                        ColumnIdent clusteredBy,
                        boolean isAlias,
                        boolean hasAutoGeneratedPrimaryKey,
                        String[] concreteIndices,
                        String[] concreteOpenIndices,
                        ClusterService clusterService,
                        IndexNameExpressionResolver indexNameExpressionResolver,
                        int numberOfShards,
                        BytesRef numberOfReplicas,
                        ImmutableMap<String, Object> tableParameters,
                        List<ColumnIdent> partitionedBy,
                        List<PartitionName> partitions,
                        ColumnPolicy columnPolicy,
                        String routingHashFunction,
                        @Nullable Version versionCreated,
                        @Nullable Version versionUpgraded,
                        boolean closed,
                        Set<Operation> supportedOperations) {
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        assert (partitionedBy.size() ==
                partitionedByColumns.size()) : "partitionedBy and partitionedByColumns must have same amount of items in list";
        this.clusterService = clusterService;
        this.columns = columns;
        this.partitionedByColumns = partitionedByColumns;
        this.generatedColumns = generatedColumns;
        this.notNullColumns = notNullColumns;
        this.indexColumns = indexColumns;
        this.references = references;
        this.analyzers = analyzers;
        this.ident = ident;
        this.primaryKeys = primaryKeys;
        this.clusteredBy = clusteredBy;
        this.concreteIndices = concreteIndices;
        this.concreteOpenIndices = concreteOpenIndices;
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        this.tableParameters = tableParameters;
        this.isAlias = isAlias;
        this.hasAutoGeneratedPrimaryKey = hasAutoGeneratedPrimaryKey;
        isPartitioned = !partitionedByColumns.isEmpty();
        this.partitionedBy = partitionedBy;
        this.partitions = partitions;
        this.columnPolicy = columnPolicy;
        this.routingHashFunction = routingHashFunction;
        this.versionCreated = versionCreated;
        this.versionUpgraded = versionUpgraded;
        this.closed = closed;
        this.supportedOperations = supportedOperations;
        if (isPartitioned) {
            tableParameterInfo = PartitionedTableParameterInfo.INSTANCE;
        } else {
            tableParameterInfo = TableParameterInfo.INSTANCE;
        }
        // scale the fetchrouting timeout by n# of partitions
        this.docColumn = new TableColumn(DocSysColumns.DOC, references);
    }

    @Nullable
    public Reference getReference(ColumnIdent columnIdent) {
        Reference reference = references.get(columnIdent);
        if (reference == null) {
            return docColumn.getReference(ident(), columnIdent);
        }
        return reference;
    }


    @Override
    public Collection<Reference> columns() {
        return columns;
    }

    public List<GeneratedReference> generatedColumns() {
        return generatedColumns;
    }

    @Override
    public RowGranularity rowGranularity() {
        return RowGranularity.DOC;
    }

    @Override
    public TableIdent ident() {
        return ident;
    }

    private void processShardRouting(Map<String, Map<String, List<Integer>>> locations, ShardRouting shardRouting) {
        String node = shardRouting.currentNodeId();
        Map<String, List<Integer>> nodeMap = locations.get(node);
        if (nodeMap == null) {
            nodeMap = new TreeMap<>();
            locations.put(shardRouting.currentNodeId(), nodeMap);
        }

        String indexName = shardRouting.getIndexName();
        List<Integer> shards = nodeMap.get(indexName);
        if (shards == null) {
            shards = new ArrayList<>();
            nodeMap.put(indexName, shards);
        }
        shards.add(shardRouting.id());
    }

    private GroupShardsIterator<ShardIterator> getShardIterators(WhereClause whereClause,
                                                  @Nullable String preference) throws IndexNotFoundException {
        String[] routingIndices;
        if (whereClause.partitions().size() > 0) {
            routingIndices = whereClause.partitions().toArray(new String[whereClause.partitions().size()]);
        } else {
            routingIndices = concreteOpenIndices;
        }
        ClusterState state = clusterService.state();
        Map<String, Set<String>> routingMap = null;
        if (whereClause.clusteredBy().isPresent()) {
            routingMap = indexNameExpressionResolver.resolveSearchRouting(
                state, whereClause.routingValues(), routingIndices);
        }
        return clusterService.operationRouting().searchShards(
            state,
            routingIndices,
            routingMap,
            preference
        );
    }

    @Override
    public Routing getRouting(final WhereClause whereClause,
                              @Nullable final String preference,
                              SessionContext sessionContext) {
        GroupShardsIterator<ShardIterator> shardIterators;
        try {
            shardIterators = getShardIterators(whereClause, preference);
        } catch (IndexNotFoundException e) {
            return new Routing(Collections.emptyMap());
        }
        final Map<String, Map<String, List<Integer>>> locations = new TreeMap<>();
        fillLocationsFromShardIterators(locations, shardIterators);
        return new Routing(locations);
    }

    private void fillLocationsFromShardIterators(Map<String, Map<String, List<Integer>>> locations,
                                                 GroupShardsIterator<ShardIterator> shardIterators) {
        ShardRouting shardRouting;
        for (ShardIterator shardIterator : shardIterators) {
            shardRouting = shardIterator.nextOrNull();
            if (shardRouting == null) {
                if (isPartitioned) {
                    // if the table is partitioned it's okay to exclude newly created index/shards
                    continue;
                }
                throw new UnavailableShardsException(shardIterator.shardId());
            }
            processShardRouting(locations, shardRouting);
        }
    }

    public List<ColumnIdent> primaryKey() {
        return primaryKeys;
    }

    @Override
    public int numberOfShards() {
        return numberOfShards;
    }

    @Override
    public BytesRef numberOfReplicas() {
        return numberOfReplicas;
    }

    @Override
    public ColumnIdent clusteredBy() {
        return clusteredBy;
    }

    public boolean hasAutoGeneratedPrimaryKey() {
        return hasAutoGeneratedPrimaryKey;
    }

    /**
     * @return true if this <code>TableInfo</code> is referenced by an alias name, false otherwise
     */
    public boolean isAlias() {
        return isAlias;
    }

    public String[] concreteIndices() {
        return concreteIndices;
    }

    /**
     * columns this table is partitioned by.
     * <p>
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     *
     * @return always a list, never null
     */
    public List<Reference> partitionedByColumns() {
        return partitionedByColumns;
    }

    /**
     * column names of columns this table is partitioned by (in dotted syntax).
     * <p>
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     *
     * @return always a list, never null
     */
    public List<ColumnIdent> partitionedBy() {
        return partitionedBy;
    }

    public List<PartitionName> partitions() {
        return partitions;
    }

    /**
     * returns <code>true</code> if this table is a partitioned table,
     * <code>false</code> otherwise
     * <p>
     * if so, {@linkplain #partitions()} returns infos about the concrete indices that make
     * up this virtual partitioned table
     */
    public boolean isPartitioned() {
        return isPartitioned;
    }

    public IndexReference indexColumn(ColumnIdent ident) {
        return indexColumns.get(ident);
    }

    public Iterator<IndexReference> indexColumns() {
        return indexColumns.values().iterator();
    }

    @Override
    public Iterator<Reference> iterator() {
        return references.values().iterator();
    }

    /**
     * return the column policy of this table
     * that defines how adding new columns will be handled.
     * <ul>
     * <li><code>STRICT</code> means no new columns are allowed
     * <li><code>DYNAMIC</code> means new columns will be added to the schema
     * <li><code>IGNORED</code> means new columns will not be added to the schema.
     * those ignored columns can only be selected.
     * </ul>
     */
    public ColumnPolicy columnPolicy() {
        return columnPolicy;
    }

    @Override
    public String routingHashFunction() {
        return routingHashFunction;
    }

    @Nullable
    @Override
    public Version versionCreated() {
        return versionCreated;
    }

    @Nullable
    @Override
    public Version versionUpgraded() {
        return versionUpgraded;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public TableParameterInfo tableParameterInfo() {
        return tableParameterInfo;
    }

    public ImmutableMap<String, Object> tableParameters() {
        return tableParameters;
    }

    @Override
    public Set<Operation> supportedOperations() {
        return supportedOperations;
    }

    public String getAnalyzerForColumnIdent(ColumnIdent ident) {
        return analyzers.get(ident);
    }

    @Nullable
    public DynamicReference getDynamic(ColumnIdent ident, boolean forWrite) {
        boolean parentIsIgnored = false;
        ColumnPolicy parentPolicy = columnPolicy();
        if (!ident.isColumn()) {
            // see if parent is strict object
            ColumnIdent parentIdent = ident.getParent();
            Reference parentInfo = null;

            while (parentIdent != null) {
                parentInfo = getReference(parentIdent);
                if (parentInfo != null) {
                    break;
                }
                parentIdent = parentIdent.getParent();
            }

            if (parentInfo != null) {
                parentPolicy = parentInfo.columnPolicy();
            }
        }

        switch (parentPolicy) {
            case DYNAMIC:
                if (!forWrite) return null;
                break;
            case STRICT:
                if (forWrite) throw new ColumnUnknownException(ident.sqlFqn(), ident());
                return null;
            case IGNORED:
                parentIsIgnored = true;
                break;
            default:
                break;
        }
        if (parentIsIgnored) {
            return new DynamicReference(new ReferenceIdent(ident(), ident), rowGranularity(), ColumnPolicy.IGNORED);
        }
        return new DynamicReference(new ReferenceIdent(ident(), ident), rowGranularity());
    }

    @Override
    public String toString() {
        return ident.fqn();
    }

    public Collection<ColumnIdent> notNullColumns() {
        return notNullColumns;
    }
}
