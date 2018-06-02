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
package com.dremio.exec.planner.acceleration.substitution;

import com.dremio.exec.planner.StatelessRelShuttleImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;

import java.util.List;

/**
 * DistinctFinder finds distincts in logical aggregates of the tree.
 */
class DistinctFinder extends StatelessRelShuttleImpl {
  private boolean foundDistinct = false;

  public boolean isFoundDistinct() {
    return foundDistinct;
  }

  public RelNode visit(LogicalAggregate aggregate) {
    List<AggregateCall> aggCallList = aggregate.getAggCallList();

    for (int i = 0; i < aggCallList.size(); i++) {
      if (aggCallList.get(i).isDistinct()) {
        foundDistinct = true;
        return aggregate;
      }
    }

    return visitChildren(aggregate);
  }

  public RelNode visit(RelNode node) {
    return visitChildren(node);
  }
}