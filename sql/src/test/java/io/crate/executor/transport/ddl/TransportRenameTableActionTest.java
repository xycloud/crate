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

package io.crate.executor.transport.ddl;

import io.crate.integrationtests.SQLTransportIntegrationTest;
import io.crate.metadata.TableIdent;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

@ESIntegTestCase.ClusterScope(numDataNodes = 1, supportsDedicatedMasters = false, numClientNodes = 0)
public class TransportRenameTableActionTest extends SQLTransportIntegrationTest {

    private TransportRenameTableAction transportRenameTableAction;

    @Before
    public void setUpTransportAndTable() throws Exception {
        transportRenameTableAction = internalCluster().getInstance(TransportRenameTableAction.class);
        execute("create table t1 (i int)");
        execute("create table p1 (i int) partitioned by (i)");
        execute("create table p11 (i int) partitioned by (i)");
        ensureYellow();
        execute("insert into p11 (i) values (1)");
        refresh();
        execute("alter table p11 close");
        execute("alter table p11 partition(i=1) open");
    }

    @Test
    public void testRenameOnOpenTableThrowsException() throws Exception {
        String defaultSchema = sqlExecutor.getDefaultSchema();
        RenameTableRequest request = new RenameTableRequest(
            TableIdent.fromIndexName(defaultSchema + ".t1"),
            TableIdent.fromIndexName(defaultSchema + ".t2"), false);

        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage(String.format("Table '%s.t1' is not closed, cannot perform a rename", defaultSchema));
        transportRenameTableAction.execute(request).actionGet(5, TimeUnit.SECONDS);
    }

    @Test
    public void testRenameOnOpenPartitionedTableThrowsException() throws Exception {
        RenameTableRequest request = new RenameTableRequest(TableIdent.fromIndexName("p1"),
            TableIdent.fromIndexName("p2"), true);

        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage("Partitioned table 'doc.p1' is not closed, cannot perform a rename");
        transportRenameTableAction.execute(request).actionGet(5, TimeUnit.SECONDS);
    }

    @Test
    public void testRenameOnPartitionedTableWithOpenPartitionsThrowsException() throws Exception {
        RenameTableRequest request = new RenameTableRequest(TableIdent.fromIndexName("p11"),
            TableIdent.fromIndexName("p12"), true);

        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage("Partition '.partitioned.p11.04132' of table 'doc.p11' is not closed, cannot perform a rename");
        transportRenameTableAction.execute(request).actionGet(5, TimeUnit.SECONDS);
    }
}
