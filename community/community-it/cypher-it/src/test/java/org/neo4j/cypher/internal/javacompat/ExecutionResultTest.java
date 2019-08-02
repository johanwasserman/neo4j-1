/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.javacompat;

import org.junit.jupiter.api.Test;

import org.neo4j.cypher.ArithmeticException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.coreapi.TopLevelTransaction;
import org.neo4j.kernel.impl.query.QueryExecutionKernelException;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.extension.ImpermanentDbmsExtension;
import org.neo4j.test.extension.Inject;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.neo4j.internal.helpers.collection.MapUtil.map;

@ImpermanentDbmsExtension
class ExecutionResultTest
{
    @Inject
    private GraphDatabaseAPI db;

    //TODO this test is not valid for compiled runtime as the transaction will be closed when the iterator was created
    @Test
    void shouldCloseTransactionsWhenIteratingResults()
    {
        // Given an execution result that has been started but not exhausted
        createNode();
        createNode();
        Result executionResult = db.execute( "CYPHER runtime=interpreted MATCH (n) RETURN n" );
        executionResult.next();
        assertThat( activeTransaction(), is( notNullValue() ) );

        // When
        executionResult.close();

        // Then
        assertThat( activeTransaction(), is( nullValue() ) );
    }

    //TODO this test is not valid for compiled runtime as the transaction will be closed when the iterator was created
    @Test
    void shouldCloseTransactionsWhenIteratingOverSingleColumn()
    {
        // Given an execution result that has been started but not exhausted
        createNode();
        createNode();
        Result executionResult = db.execute( "CYPHER runtime=interpreted MATCH (n) RETURN n" );
        ResourceIterator<Node> resultIterator = executionResult.columnAs( "n" );
        resultIterator.next();
        assertThat( activeTransaction(), is( notNullValue() ) );

        // When
        resultIterator.close();

        // Then
        assertThat( activeTransaction(), is( nullValue() ) );
    }

    @Test
    void shouldThrowAppropriateException()
    {
        try
        {
            db.execute( "RETURN rand()/0" ).next();
        }
        catch ( QueryExecutionException ex )
        {
            assertThat( ex.getCause(), instanceOf( QueryExecutionKernelException.class ) );
            assertThat( ex.getCause().getCause(), instanceOf( ArithmeticException.class ) );
        }
    }

    @Test
    void shouldThrowAppropriateExceptionAlsoWhenVisiting()
    {
        try
        {
            db.execute( "RETURN rand()/0" ).accept( row -> true );
        }
        catch ( QueryExecutionException ex )
        {
            assertThat( ex.getCause(), instanceOf( QueryExecutionKernelException.class ) );
            assertThat( ex.getCause().getCause(), instanceOf( ArithmeticException.class ) );
        }
    }

    private void createNode()
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode();
            tx.success();
        }
    }

    @Test
    void shouldHandleListsOfPointsAsInput()
    {
        // Given
        Point point1 =
                (Point) db.execute( "RETURN point({latitude: 12.78, longitude: 56.7}) as point" ).next().get( "point" );
        Point point2 =
                (Point) db.execute( "RETURN point({latitude: 12.18, longitude: 56.2}) as point" ).next().get( "point" );

        // When
        double distance = (double) db.execute( "RETURN distance({points}[0], {points}[1]) as dist",
                map( "points", asList( point1, point2 ) ) ).next().get( "dist" );
        // Then
        assertThat( Math.round( distance ), equalTo( 86107L ) );
    }

    @Test
    void shouldHandleMapWithPointsAsInput()
    {
        // Given
        Point point1 = (Point) db.execute( "RETURN point({latitude: 12.78, longitude: 56.7}) as point"  ).next().get( "point" );
        Point point2 = (Point) db.execute( "RETURN point({latitude: 12.18, longitude: 56.2}) as point"  ).next().get( "point" );

        // When
        double distance = (double) db.execute( "RETURN distance({points}['p1'], {points}['p2']) as dist",
                map( "points", map("p1", point1, "p2", point2) ) ).next().get( "dist" );
        // Then
        assertThat(Math.round( distance ), equalTo(86107L));
    }

    @Test
    void shouldHandleColumnAsWithNull()
    {
        assertThat( db.execute( "RETURN toLower(null) AS lower" ).<String>columnAs( "lower" ).next(), nullValue() );
    }

    private TopLevelTransaction activeTransaction()
    {
        ThreadToStatementContextBridge bridge = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );
        KernelTransaction kernelTransaction = bridge.getKernelTransactionBoundToThisThread( false, db.databaseId() );
        return kernelTransaction == null ? null : new TopLevelTransaction( kernelTransaction );
    }
}
