/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.fragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.util.DremioStringUtils;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.expr.fn.FunctionLookupContext;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.MinorFragmentEndpoint;
import com.dremio.exec.physical.PhysicalOperatorSetupException;
import com.dremio.exec.physical.base.AbstractPhysicalVisitor;
import com.dremio.exec.physical.base.Exchange.ParallelizationDependency;
import com.dremio.exec.physical.base.FragmentRoot;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.Receiver;
import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.fragment.Fragment.ExchangeFragmentPair;
import com.dremio.exec.planner.fragment.Materializer.IndexedFragmentNode;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.proto.CoordExecRPC.Collector;
import com.dremio.exec.proto.CoordExecRPC.FragmentCodec;
import com.dremio.exec.proto.CoordExecRPC.IncomingMinorFragment;
import com.dremio.exec.proto.CoordExecRPC.PlanFragment;
import com.dremio.exec.proto.CoordExecRPC.QueryContextInformation;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.server.options.OptionList;
import com.dremio.exec.server.options.OptionManager;
import com.dremio.exec.work.QueryWorkUnit;
import com.dremio.exec.work.foreman.ForemanSetupException;
import com.dremio.sabot.rpc.user.UserSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;

/**
 * The simple parallelizer determines the level of parallelization of a plan based on the cost of the underlying
 * operations.  It doesn't take into account system load or other factors.  Based on the cost of the query, the
 * parallelization for each major fragment will be determined.  Once the amount of parallelization is done, assignment
 * is done based on round robin assignment ordered by operator affinity (locality) to available execution SabotNodes.
 */
public class SimpleParallelizer implements ParallelizationParameters {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SimpleParallelizer.class);

  private final long parallelizationThreshold;
  private final int maxWidthPerNode;
  private final int maxGlobalWidth;
  private final double affinityFactor;
  private final boolean useNewAssignmentCreator;
  private final double assignmentCreatorBalanceFactor;
  private final AttemptObserver observer;
  private final ExecutionNodeMap executionMap;
  private final FragmentCodec fragmentCodec;

  public SimpleParallelizer(QueryContext context, AttemptObserver observer) {
    OptionManager optionManager = context.getOptions();
    long sliceTarget = context.getPlannerSettings().getSliceTarget();
    this.parallelizationThreshold = sliceTarget > 0 ? sliceTarget : 1;

    final long configuredMaxWidthPerNode = context.getClusterResourceInformation().getAverageExecutorCores(optionManager);
    final double maxWidthFactor = context.getWorkStatsProvider().get().getMaxWidthFactor();
    this.maxWidthPerNode = (int) Math.max(1, configuredMaxWidthPerNode * maxWidthFactor);

    if (logger.isDebugEnabled() && maxWidthFactor < 1) {
      final float clusterLoad = context.getWorkStatsProvider().get().getClusterLoad();
      logger.debug("Cluster load {} exceeded cutoff, max_width_factor = {}. current max_width = {}",
        clusterLoad, maxWidthFactor, this.maxWidthPerNode);
    }
    this.executionMap = new ExecutionNodeMap(context.getActiveEndpoints());
    this.maxGlobalWidth = (int) optionManager.getOption(ExecConstants.MAX_WIDTH_GLOBAL);
    this.affinityFactor = optionManager.getOption(ExecConstants.AFFINITY_FACTOR);
    this.useNewAssignmentCreator = !optionManager.getOption(ExecConstants.OLD_ASSIGNMENT_CREATOR);
    this.assignmentCreatorBalanceFactor = optionManager.getOption(ExecConstants.ASSIGNMENT_CREATOR_BALANCE_FACTOR);
    this.observer = observer;
    this.fragmentCodec = FragmentCodec.valueOf(optionManager.getOption(ExecConstants.FRAGMENT_CODEC).toUpperCase());

  }

  public SimpleParallelizer(long parallelizationThreshold, int maxWidthPerNode, int maxGlobalWidth, double affinityFactor, AttemptObserver observer, boolean useNewAssignmentCreator, double assignmentCreatorBalanceFactor) {
    this.executionMap = new ExecutionNodeMap(Collections.<NodeEndpoint>emptyList());
    this.parallelizationThreshold = parallelizationThreshold;
    this.maxWidthPerNode = maxWidthPerNode;
    this.maxGlobalWidth = maxGlobalWidth;
    this.affinityFactor = affinityFactor;
    this.observer = observer;
    this.useNewAssignmentCreator = useNewAssignmentCreator;
    this.assignmentCreatorBalanceFactor = assignmentCreatorBalanceFactor;
    this.fragmentCodec = FragmentCodec.NONE;
  }

  @Override
  public long getSliceTarget() {
    return parallelizationThreshold;
  }

  @Override
  public int getMaxWidthPerNode() {
    return maxWidthPerNode;
  }

  @Override
  public int getMaxGlobalWidth() {
    return maxGlobalWidth;
  }

  @Override
  public double getAffinityFactor() {
    return affinityFactor;
  }

  @Override
  public boolean useNewAssignmentCreator() {
    return useNewAssignmentCreator;
  }

  @Override
  public double getAssignmentCreatorBalanceFactor(){
    return assignmentCreatorBalanceFactor;
  }

  /**
   * Generate a set of assigned fragments based on the provided fragment tree. Do not allow parallelization stages
   * to go beyond the global max width.
   *
   * @param options         Option list
   * @param foremanNode     The driving/foreman node for this query.  (this node)
   * @param queryId         The queryId for this query.
   * @param activeEndpoints The list of endpoints to consider for inclusion in planning this query.
   * @param reader          Tool used to read JSON plans
   * @param rootFragment    The root node of the PhysicalPlan that we will be parallelizing.
   * @param session         UserSession of user who launched this query.
   * @param queryContextInfo Info related to the context when query has started.
   * @return The list of generated PlanFragment protobuf objects to be assigned out to the individual nodes.
   * @throws ExecutionSetupException
   */
  public List<PlanFragment> getFragments(
      OptionList options,
      NodeEndpoint foremanNode,
      QueryId queryId,
      Collection<NodeEndpoint> activeEndpoints,
      PhysicalPlanReader reader,
      Fragment rootFragment,
      UserSession session,
      QueryContextInformation queryContextInfo,
      FunctionLookupContext functionLookupContext) throws ExecutionSetupException {
    observer.planParallelStart();
    final Stopwatch stopwatch = Stopwatch.createStarted();
    final PlanningSet planningSet = getFragmentsHelper(activeEndpoints, rootFragment);
    observer.planParallelized(planningSet);
    stopwatch.stop();
    observer.planAssignmentTime(stopwatch.elapsed(TimeUnit.MILLISECONDS));
    stopwatch.start();
    List<PlanFragment> fragments = generateWorkUnit(options, foremanNode, queryId, reader, rootFragment, planningSet, session, queryContextInfo, functionLookupContext);
    stopwatch.stop();
    observer.planGenerationTime(stopwatch.elapsed(TimeUnit.MILLISECONDS));
    observer.plansDistributionComplete(new QueryWorkUnit(fragments));
    return fragments;
  }

  /**
   * Helper method to reuse the code for QueryWorkUnit(s) generation
   * @param activeEndpoints
   * @param rootFragment
   * @return
   * @throws ExecutionSetupException
   */
  protected PlanningSet getFragmentsHelper(Collection<NodeEndpoint> activeEndpoints, Fragment rootFragment) throws ExecutionSetupException {

    PlanningSet planningSet = new PlanningSet();

    initFragmentWrappers(rootFragment, planningSet);

    final Set<Wrapper> leafFragments = constructFragmentDependencyGraph(planningSet);

    // Start parallelizing from leaf fragments
    for (Wrapper wrapper : leafFragments) {
      parallelizeFragment(wrapper, planningSet, activeEndpoints);
    }

    return planningSet;
  }

  // For every fragment, create a Wrapper in PlanningSet.
  @VisibleForTesting
  public void initFragmentWrappers(Fragment rootFragment, PlanningSet planningSet) {
    planningSet.get(rootFragment);

    for(ExchangeFragmentPair fragmentPair : rootFragment) {
      initFragmentWrappers(fragmentPair.getNode(), planningSet);
    }
  }

  /**
   * Based on the affinity of the Exchange that separates two fragments, setup fragment dependencies.
   *
   * @param planningSet
   * @return Returns a list of leaf fragments in fragment dependency graph.
   */
  private static Set<Wrapper> constructFragmentDependencyGraph(PlanningSet planningSet) {

    // Set up dependency of fragments based on the affinity of exchange that separates the fragments.
    for(Wrapper currentFragmentWrapper : planningSet) {
      ExchangeFragmentPair sendingExchange = currentFragmentWrapper.getNode().getSendingExchangePair();
      if (sendingExchange != null) {
        ParallelizationDependency dependency = sendingExchange.getExchange().getParallelizationDependency();
        Wrapper receivingFragmentWrapper = planningSet.get(sendingExchange.getNode());

        if (dependency == ParallelizationDependency.RECEIVER_DEPENDS_ON_SENDER) {
          receivingFragmentWrapper.addFragmentDependency(currentFragmentWrapper);
        } else if (dependency == ParallelizationDependency.SENDER_DEPENDS_ON_RECEIVER) {
          currentFragmentWrapper.addFragmentDependency(receivingFragmentWrapper);
        }
      }
    }

    // Identify leaf fragments. Leaf fragments are fragments that have no other fragments depending on them for
    // parallelization info. First assume all fragments are leaf fragments. Go through the fragments one by one and
    // remove the fragment on which the current fragment depends on.
    final Set<Wrapper> roots = Sets.newHashSet();
    for(Wrapper w : planningSet) {
      roots.add(w);
    }

    for(Wrapper wrapper : planningSet) {
      final List<Wrapper> fragmentDependencies = wrapper.getFragmentDependencies();
      if (fragmentDependencies != null && fragmentDependencies.size() > 0) {
        for(Wrapper dependency : fragmentDependencies) {
          if (roots.contains(dependency)) {
            roots.remove(dependency);
          }
        }
      }
    }

    return roots;
  }

  /**
   * Helper method for parallelizing a given fragment. Dependent fragments are parallelized first before
   * parallelizing the given fragment.
   */
  private void parallelizeFragment(Wrapper fragmentWrapper, PlanningSet planningSet,
      Collection<NodeEndpoint> activeEndpoints) throws PhysicalOperatorSetupException {
    // If the fragment is already parallelized, return.
    if (fragmentWrapper.isEndpointsAssignmentDone()) {
      return;
    }

    // First parallelize fragments on which this fragment depends on.
    final List<Wrapper> fragmentDependencies = fragmentWrapper.getFragmentDependencies();
    if (fragmentDependencies != null && fragmentDependencies.size() > 0) {
      for(Wrapper dependency : fragmentDependencies) {
        parallelizeFragment(dependency, planningSet, activeEndpoints);
      }
    }

    // Find stats. Stats include various factors including cost of physical operators, parallelizability of
    // work in physical operator and affinity of physical operator to certain nodes.
    fragmentWrapper.getNode().getRoot().accept(new StatsCollector(planningSet, executionMap), fragmentWrapper);

    fragmentWrapper.getStats().getDistributionAffinity()
        .getFragmentParallelizer()
        .parallelizeFragment(fragmentWrapper, this, activeEndpoints);
  }

  protected List<PlanFragment> generateWorkUnit(
      OptionList options,
      NodeEndpoint foremanNode,
      QueryId queryId,
      PhysicalPlanReader reader,
      Fragment rootNode,
      PlanningSet planningSet,
      UserSession session,
      QueryContextInformation queryContextInfo,
      FunctionLookupContext functionLookupContext) throws ExecutionSetupException {

    final List<PlanFragment> fragments = Lists.newArrayList();
    // now we generate all the individual plan fragments and associated assignments. Note, we need all endpoints
    // assigned before we can materialize, so we start a new loop here rather than utilizing the previous one.
    for (Wrapper wrapper : planningSet) {
      Fragment node = wrapper.getNode();
      final PhysicalOperator physicalOperatorRoot = node.getRoot();
      boolean isRootNode = rootNode == node;

      if (isRootNode && wrapper.getWidth() != 1) {
        throw new ForemanSetupException(String.format("Failure while trying to setup fragment. " +
                "The root fragment must always have parallelization one. In the current case, the width was set to %d.",
                wrapper.getWidth()));
      }
      // a fragment is self driven if it doesn't rely on any other exchanges.
      boolean isLeafFragment = node.getReceivingExchangePairs().size() == 0;

      // Create a minorFragment for each major fragment.
      for (int minorFragmentId = 0; minorFragmentId < wrapper.getWidth(); minorFragmentId++) {
        IndexedFragmentNode iNode = new IndexedFragmentNode(minorFragmentId, wrapper);
        wrapper.resetAllocation();
        PhysicalOperator op = physicalOperatorRoot.accept(new Materializer(functionLookupContext, wrapper.getSplitSets()), iNode);
        Preconditions.checkArgument(op instanceof FragmentRoot);
        FragmentRoot root = (FragmentRoot) op;

        // get plan as JSON
        ByteString plan;
        ByteString optionsData;
        try {
          plan = reader.writeJsonBytes(root, fragmentCodec);
          optionsData = reader.writeJsonBytes(options, fragmentCodec);
        } catch (JsonProcessingException e) {
          throw new ForemanSetupException("Failure while trying to convert fragment into json.", e);
        }

        FragmentHandle handle = FragmentHandle //
            .newBuilder() //
            .setMajorFragmentId(wrapper.getMajorFragmentId()) //
            .setMinorFragmentId(minorFragmentId) //
            .setQueryId(queryId) //
            .build();

        PlanFragment fragment = PlanFragment.newBuilder() //
            .setForeman(foremanNode) //
            .setFragmentJson(plan) //
            .setHandle(handle) //
            .setAssignment(wrapper.getAssignedEndpoint(minorFragmentId)) //
            .setLeafFragment(isLeafFragment) //
            .setContext(queryContextInfo)
            .setMemInitial(wrapper.getInitialAllocation())//
            .setMemMax(wrapper.getMaxAllocation())
            .setOptionsJson(optionsData)
            .setCredentials(session.getCredentials())
            .addAllCollector(CountRequiredFragments.getCollectors(root))
            .setPriority(queryContextInfo.getPriority())
            .setFragmentCodec(fragmentCodec)
            .build();

        if(logger.isTraceEnabled()){
          logger.trace("Remote fragment:\n {}", DremioStringUtils.unescapeJava(fragment.toString()));
        }
        fragments.add(fragment);
      }

    }

    return fragments;
  }


  /**
   * Designed to setup initial values for arriving fragment accounting.
   */
  protected static class CountRequiredFragments extends AbstractPhysicalVisitor<Void, List<Collector>, RuntimeException> {
    private static final CountRequiredFragments INSTANCE = new CountRequiredFragments();

    public static List<Collector> getCollectors(PhysicalOperator root) {
      List<Collector> collectors = Lists.newArrayList();
      root.accept(INSTANCE, collectors);
      return collectors;
    }

    @Override
    public Void visitReceiver(Receiver receiver, List<Collector> collectors) throws RuntimeException {
      List<MinorFragmentEndpoint> endpoints = receiver.getProvidingEndpoints();
      List<IncomingMinorFragment> list = new ArrayList<>(endpoints.size());
      for (MinorFragmentEndpoint ep : endpoints) {
        list.add(IncomingMinorFragment.newBuilder().setEndpoint(ep.getEndpoint()).setMinorFragment(ep.getId()).build());
      }

      collectors.add(Collector.newBuilder()
        .setIsSpooling(receiver.isSpooling())
        .setOppositeMajorFragmentId(receiver.getOppositeMajorFragmentId())
        .setSupportsOutOfOrder(receiver.supportsOutOfOrderExchange())
          .addAllIncomingMinorFragment(list)
          .build());
      return null;
    }

    @Override
    public Void visitOp(PhysicalOperator op, List<Collector> collectors) throws RuntimeException {
      for (PhysicalOperator o : op) {
        o.accept(this, collectors);
      }
      return null;
    }

  }
}