/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal


import org.neo4j.cypher.internal.compatibility.v3_4.runtime._
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.compiled.EnterpriseRuntimeContext
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.executionplan.StandardInternalExecutionResult
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.executionplan.StandardInternalExecutionResult.IterateByAccepting
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.phases.CompilationState
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.slotted.expressions.SlottedExpressionConverters
import org.neo4j.cypher.internal.compiler.v3_4.phases.LogicalPlanState
import org.neo4j.cypher.internal.frontend.v3_4.PlannerName
import org.neo4j.cypher.internal.frontend.v3_4.notification.InternalNotification
import org.neo4j.cypher.internal.frontend.v3_4.phases.CompilationPhaseTracer.CompilationPhase
import org.neo4j.cypher.internal.frontend.v3_4.phases.{CompilationPhaseTracer, Condition, Phase}
import org.neo4j.cypher.internal.planner.v3_4.spi.{GraphStatistics, PlanContext}
import org.neo4j.cypher.internal.runtime.interpreted.commands.convert.{CommunityExpressionConverter, ExpressionConverters}
import org.neo4j.cypher.internal.runtime.planDescription.InternalPlanDescription.Arguments.{Runtime, RuntimeImpl}
import org.neo4j.cypher.internal.runtime.planDescription.{InternalPlanDescription, LogicalPlan2PlanDescription}
import org.neo4j.cypher.internal.runtime.vectorized.dispatcher.{Dispatcher, SingleThreadedExecutor}
import org.neo4j.cypher.internal.runtime.vectorized.{Pipeline, PipelineBuilder}
import org.neo4j.cypher.internal.runtime.{QueryStatistics, _}
import org.neo4j.cypher.internal.util.v3_4.TaskCloser
import org.neo4j.cypher.internal.v3_4.logical.plans.{LogicalPlan, LogicalPlanId}
import org.neo4j.cypher.result.QueryResult.QueryResultVisitor
import org.neo4j.graphdb._
import org.neo4j.values.virtual.MapValue

object BuildVectorizedExecutionPlan extends Phase[EnterpriseRuntimeContext, LogicalPlanState, CompilationState] {
  override def phase: CompilationPhaseTracer.CompilationPhase = CompilationPhase.PIPE_BUILDING

  override def description: String = "build pipes"

  override def process(from: LogicalPlanState, context: EnterpriseRuntimeContext): CompilationState = {
    val (physicalPlan, pipelines) = rewritePlan(context, from.logicalPlan)
    val converters: ExpressionConverters = new ExpressionConverters(SlottedExpressionConverters, CommunityExpressionConverter)
    val operatorBuilder = new PipelineBuilder(pipelines, converters)
    val operators = operatorBuilder.create(physicalPlan)
    val dispatcher =
      if (context.debugOptions.contains("singlethreaded")) new SingleThreadedExecutor()
      else context.dispatcher
    val fieldNames = from.statement().returnColumns.toArray

    val execPlan = VectorizedExecutionPlan(from.plannerName, operators, pipelines, physicalPlan, fieldNames, dispatcher)
    new CompilationState(from, Some(execPlan))
  }

  private def rewritePlan(context: EnterpriseRuntimeContext, beforeRewrite: LogicalPlan) = {
    val pipelines: Map[LogicalPlanId, PipelineInformation] = SlotAllocation.allocateSlots(beforeRewrite)
    val slottedRewriter = new SlottedRewriter(context.planContext)
    val logicalPlan = slottedRewriter(beforeRewrite, pipelines)
    (logicalPlan, pipelines)
  }

  override def postConditions: Set[Condition] = Set.empty

  case class VectorizedExecutionPlan(plannerUsed: PlannerName,
                                     operators: Pipeline,
                                     pipelineInformation: Map[LogicalPlanId, PipelineInformation],
                                     physicalPlan: LogicalPlan,
                                     fieldNames: Array[String],
                                     dispatcher: Dispatcher) extends executionplan.ExecutionPlan {
    override def run(queryContext: QueryContext, planType: ExecutionMode, params: MapValue): InternalExecutionResult = {
      val taskCloser = new TaskCloser
      taskCloser.addTask(queryContext.transactionalContext.close)
      val planDescription =
        () => LogicalPlan2PlanDescription(physicalPlan, plannerUsed)
          .addArgument(Runtime(MorselRuntimeName.toTextOutput))
          .addArgument(RuntimeImpl(MorselRuntimeName.name))

      new VectorizedOperatorExecutionResult(operators, physicalPlan, planDescription, queryContext,
                                            params, fieldNames, taskCloser, dispatcher)
    }

    override def isPeriodicCommit: Boolean = false

    override def isStale(lastTxId: () => Long, statistics: GraphStatistics): Boolean = false

    override def runtimeUsed: RuntimeName = MorselRuntimeName

    override def notifications(planContext: PlanContext): Seq[InternalNotification] = Seq.empty
  }

}

class VectorizedOperatorExecutionResult(operators: Pipeline,
                                        logicalPlan: LogicalPlan,
                                        executionPlanBuilder: () => InternalPlanDescription,
                                        queryContext: QueryContext,
                                        params: MapValue,
                                        override val fieldNames: Array[String],
                                        taskCloser: TaskCloser,
                                        dispatcher: Dispatcher) extends StandardInternalExecutionResult(queryContext, ProcedureRuntimeName, Some(taskCloser)) with IterateByAccepting {


  override def accept[E <: Exception](visitor: QueryResultVisitor[E]): Unit = dispatcher.execute(operators, queryContext, params)(visitor)


  override def queryStatistics(): runtime.QueryStatistics = queryContext.getOptStatistics.getOrElse(QueryStatistics())

  override def executionPlanDescription(): InternalPlanDescription = executionPlanBuilder()

  override def queryType: InternalQueryType = READ_ONLY

  override def executionMode: ExecutionMode = NormalMode

  override def notifications: Iterable[Notification] = ???

  override def withNotifications(notification: Notification*): InternalExecutionResult = this
}

