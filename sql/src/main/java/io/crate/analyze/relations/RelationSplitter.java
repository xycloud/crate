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

package io.crate.analyze.relations;

import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import io.crate.analyze.HavingClause;
import io.crate.analyze.OrderBy;
import io.crate.analyze.QuerySpec;
import io.crate.analyze.WhereClause;
import io.crate.analyze.fetch.FetchFieldExtractor;
import io.crate.analyze.symbol.Aggregations;
import io.crate.analyze.symbol.DefaultTraversalSymbolVisitor;
import io.crate.analyze.symbol.Field;
import io.crate.analyze.symbol.FieldsVisitor;
import io.crate.analyze.symbol.Literal;
import io.crate.analyze.symbol.MatchPredicate;
import io.crate.analyze.symbol.Symbol;
import io.crate.operation.operator.AndOperator;
import io.crate.planner.Limits;
import io.crate.sql.tree.QualifiedName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public final class RelationSplitter {

    private final QuerySpec querySpec;
    private final Set<Symbol> requiredForMerge = new HashSet<>();
    private final Map<AnalyzedRelation, QuerySpec> specs;
    private final Map<QualifiedName, AnalyzedRelation> relations;
    private final List<JoinPair> joinPairs;
    private final List<Symbol> joinConditions;
    private final Set<QualifiedName> relationPartOfJoinConditions;
    private Set<Field> canBeFetched;

    public RelationSplitter(QuerySpec querySpec,
                            Collection<? extends AnalyzedRelation> relations,
                            List<JoinPair> joinPairs) {
        this.querySpec = querySpec;
        specs = new IdentityHashMap<>(relations.size());
        this.relations = new HashMap<>(relations.size());
        for (AnalyzedRelation relation : relations) {
            specs.put(relation, new QuerySpec());
            this.relations.put(relation.getQualifiedName(), relation);
        }
        this.joinPairs = joinPairs;
        joinConditions = new ArrayList<>(joinPairs.size());
        relationPartOfJoinConditions = new HashSet<>(joinPairs.size());
        for (JoinPair joinPair : joinPairs) {
            if (joinPair.condition() != null) {
                JoinConditionValidator.validate(joinPair.condition());
                joinConditions.add(joinPair.condition());
                relationPartOfJoinConditions.add(joinPair.left());
                relationPartOfJoinConditions.add(joinPair.right());
            }
        }
    }

    public Set<Symbol> requiredForMerge() {
        return requiredForMerge;
    }

    public Set<Field> canBeFetched() {
        return canBeFetched;
    }

    public QuerySpec getSpec(AnalyzedRelation relation) {
        return specs.get(relation);
    }

    public void process() {
        processOrderBy();
        processWhere();
        processOutputs();
    }

    private QuerySpec getSpec(QualifiedName relationName) {
        return specs.get(relations.get(relationName));
    }

    private void processOutputs() {
        SetMultimap<AnalyzedRelation, Symbol> fieldsByRelation = Multimaps.newSetMultimap(
            new IdentityHashMap<AnalyzedRelation, Collection<Symbol>>(specs.size()), LinkedHashSet::new);
        Consumer<Field> addFieldToMap = f -> fieldsByRelation.put(f.relation(), f);

        Optional<List<Symbol>> groupBy = querySpec.groupBy();
        if (groupBy.isPresent()) {
            FieldsVisitor.visitFields(groupBy.get(), addFieldToMap);
        }
        Optional<HavingClause> having = querySpec.having();
        if (having.isPresent()) {
            HavingClause havingClause = having.get();
            if (havingClause.hasQuery()) {
                FieldsVisitor.visitFields(havingClause.query(), addFieldToMap);
            }
        }

        if (querySpec.where().hasQuery()) {
            FieldsVisitor.visitFields(querySpec.where().query(), addFieldToMap);
        }

        // collect all fields from all join conditions
        FieldsVisitor.visitFields(joinConditions, addFieldToMap);

        // push down the limit and offset only if there is no filtering or ordering after the join
        // and only if the relations are not part of a join condition
        Optional<Symbol> limit = querySpec.limit();
        boolean filterNeeded = querySpec.where().hasQuery() && !(querySpec.where().query() instanceof Literal);
        if (limit.isPresent() && !filterNeeded && !querySpec.orderBy().isPresent()) {
            Optional<Symbol> limitAndOffset = Limits.mergeAdd(limit, querySpec.offset());
            for (AnalyzedRelation rel : Sets.difference(specs.keySet(), fieldsByRelation.keySet())) {
                if (!relationPartOfJoinConditions.contains(rel.getQualifiedName())) {
                    QuerySpec spec = specs.get(rel);
                    // If it's a sub-select it might already have a limit
                    if (!spec.limit().isPresent()) {
                        spec.limit(limitAndOffset);
                    }
                }
            }
        }

        // add all order by symbols to context outputs
        for (Map.Entry<AnalyzedRelation, QuerySpec> entry : specs.entrySet()) {
            QuerySpec querySpec = entry.getValue();
            if (querySpec.orderBy().isPresent()) {
                FieldsVisitor.visitFields(querySpec.orderBy().get().orderBySymbols(), addFieldToMap);
            }
        }

        // capture items from the outputs
        canBeFetched = FetchFieldExtractor.process(querySpec.outputs(), fieldsByRelation);

        for (Symbol symbol : requiredForMerge) {
            FieldsVisitor.visitFields(symbol, f -> {
                canBeFetched.remove(f);
                addFieldToMap.accept(f);
            });
        }

        FieldsVisitor.visitFields(querySpec.outputs(), addFieldToMap);

        // generate the outputs of the subSpecs
        for (Map.Entry<AnalyzedRelation, QuerySpec> entry : specs.entrySet()) {
            Collection<Symbol> fields = fieldsByRelation.get(entry.getKey());
            assert entry.getValue().outputs() == null : "entry.getValue().outputs() must not be null";
            entry.getValue().outputs(new ArrayList<>(fields));
        }
    }

    private void processWhere() {
        // Push down NO_MATCH to all relations
        if (querySpec.where().noMatch()) {
            for (QuerySpec querySpec : specs.values()) {
                querySpec.where(WhereClause.NO_MATCH);
            }
            return;
        }

        if (!querySpec.where().hasQuery()) {
            return;
        }

        Symbol query = querySpec.where().query();
        assert query != null : "query must not be null";

        Map<Set<QualifiedName>, Symbol> splitQueries = QuerySplitter.split(query);
        for (Map.Entry<QualifiedName, AnalyzedRelation> relationEntry : relations.entrySet()) {
            QualifiedName relationName = relationEntry.getKey();
            if (JoinPairs.isOuterRelation(relationName, joinPairs)) {
                /* If the query involves a relation that is part of an outer join is cannot be pushed down.
                 * The predicate must be applied *after* the join, because a outer join can *create* null values.
                 *
                 * The predicate could filter these null values if it's applied POST-join, but wouldn't if it's applied pre-join
                 */
                continue;
            }
            AnalyzedRelation relation = relationEntry.getValue();
            Symbol queryForRelation = splitQueries.remove(Collections.singleton(relationName));
            if (queryForRelation == null) {
                continue;
            }
            QuerySpec qs = getSpec(relationName);

            // Case of subselect
            if (!(relation instanceof AbstractTableRelation)) {
                applyAsWhereOrHaving(qs, queryForRelation, (QueriedRelation) relation);
            } else {
                qs.where(qs.where().add(queryForRelation));
            }
        }
        if (splitQueries.isEmpty()) {
            querySpec.where(WhereClause.MATCH_ALL);
        } else {
            Symbol newQuery = AndOperator.join(splitQueries.values());
            JoinConditionValidator.validate(newQuery);
            querySpec.where(new WhereClause(newQuery));
        }
    }

    private static void applyAsWhereOrHaving(QuerySpec qs, Symbol mergedQuery, QueriedRelation relation) {
        boolean[] hasAggregations = new boolean[] {false};
        FieldsVisitor.visitFields(mergedQuery, f -> {
            hasAggregations[0] |= Aggregations.containsAggregation(
                relation.querySpec().outputs().get(f.index()));
        });
        if (hasAggregations[0]) {
            if (qs.having().isPresent()) {
                qs.having().get().add(mergedQuery);
            } else {
                qs.having(new HavingClause(mergedQuery));
            }
        } else {
            qs.where(qs.where().add(mergedQuery));
        }
    }

    private void processOrderBy() {
        Optional<OrderBy> optOrderBy = querySpec.orderBy();
        if (!optOrderBy.isPresent() || querySpec.hasAggregates() || querySpec.groupBy().isPresent()) {
            return;
        }
        /* Because a nested loop preserves the ordering of the input-relations
         * it would be possible to move the orderBy to the subRelation
         *
         * IF:
         *   - the order by expressions only operate using fields from a single relation
         *   - that relation happens to be on the left-side of the join
         *   - the relation is *not* involved in a outer join (outer joins may create null rows - breaking the ordering)
         *
         * Currently the ManyTableConsumer may re-order relations - it has some logic to honor the order of the relations
         * based on the ORDER BY occurrences, but that's not working if the ORDER BY is *moved* instead of *replicated* child.
         */
        OrderBy orderBy = optOrderBy.get();
        requiredForMerge.addAll(orderBy.orderBySymbols());
    }

    private final static class JoinConditionValidator extends DefaultTraversalSymbolVisitor<Void, Symbol> {

        private static final JoinConditionValidator INSTANCE = new JoinConditionValidator();

        /**
         * @throws IllegalArgumentException thrown if the join condition is not valid
         */
        public static void validate(Symbol joinCondition) {
            if (joinCondition != null) {
                INSTANCE.process(joinCondition, null);
            }
        }

        @Override
        public Symbol visitMatchPredicate(MatchPredicate matchPredicate, Void context) {
            throw new IllegalArgumentException("Cannot use MATCH predicates on columns of 2 different relations " +
                                               "if it cannot be logically applied on each of them separately");
        }
    }
}
