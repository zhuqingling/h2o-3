package hex.kmeans;

import water.Iced;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Polynomial implementation in average, exponential in the worst case - slow performance.
 * Calculate Minimal Cost Flow problem using simplex method with go through spanning tree.
 * The sum of constraints are smaller the time is faster - it uses MCF until all constraints are satisfied then use standard K-means.
 */
class KMeansSimplexSolver {
    public Frame _weights; // input data + weight column + calculated distances from all points to all centres + edge indices + columns to store result of cluster assignments
    public double _sumWeights; // calculated sum of all weights to calculate maximal capacity value
    public boolean _hasWeightsColumn; // weight column existence flag
    public long _numberOfNonZeroWeightPoints; //if weights columns is set, how many rows has non zero weight
    
    public int _constraintsLength;
    public long _numberOfPoints;
    public long _edgeSize;
    public long _nodeSize;
    public long _resultSize;

    // Input graph to store K-means configuration
    public Vec.Reader _demandsReader; // store demand of all nodes (-1 for data points, constraints values for constraints nodes, )
    public Vec.Reader _capacitiesReader; // store capacities of all edges + edges from all node to leader node
    public double _maxAbsDemand; // maximal absolute demand to calculate maximal capacity value

    // Spanning tree to calculate min cost flow
    public SpanningTree tree;

    /**
     * Construct K-means simplex solver.
     * @param constrains array of constraints
     * @param weights input data + weight column + calculated distances from all points to all centres + edge indices + columns to store result of cluster assignments 
     * @param sumDistances calculated sum of all weights to calculate maximal capacity value
     * @param hasWeights weight column existence flag
     * @param numberOfNonZeroWeightPoints if weights columns is set, how many rows has non zero weight                   
     */
    public KMeansSimplexSolver(int[] constrains, Frame weights, double sumDistances, boolean hasWeights, long numberOfNonZeroWeightPoints) {
        this._numberOfPoints = weights.numRows();
        this._nodeSize = this._numberOfPoints + constrains.length + 1;
        this._edgeSize = _numberOfPoints * constrains.length + constrains.length;
        this._constraintsLength = constrains.length;

        Vec demands = Vec.makeCon(0, _nodeSize, Vec.T_NUM);
        Vec capacities = Vec.makeCon(0, _edgeSize + _nodeSize, Vec.T_NUM);
        this._resultSize = this._numberOfPoints * _constraintsLength;
        this._hasWeightsColumn = hasWeights;
        this._numberOfNonZeroWeightPoints = numberOfNonZeroWeightPoints;
        
        this._weights = weights;
        this._sumWeights = sumDistances;

        long constraintsSum = 0;
        _maxAbsDemand = Double.MIN_VALUE;
        Vec.Writer demandWriter = demands.open();
        for (long i = 0; i < _nodeSize; i++) {
            if (i < _numberOfPoints) {
                demandWriter.set(i, -1);
            } else {
                long tmpDemand;
                if (i < _nodeSize - 1) {
                    tmpDemand = constrains[(int)(i - _numberOfPoints)];
                    constraintsSum += constrains[(int)(i - _numberOfPoints)];
                } else {
                    tmpDemand = _numberOfNonZeroWeightPoints - constraintsSum;
                }
                demandWriter.set(i, tmpDemand);
                if (Math.abs(tmpDemand) > _maxAbsDemand) {
                    _maxAbsDemand = Math.abs(tmpDemand);
                }
            }
        }
        demandWriter.close();
        
        int edgeIndexStart = _weights.numCols() - 3 - _constraintsLength;
        long edgeIndex = 0; 
        for (long i = 0; i < _weights.numRows(); i++) {
            for(int j=0; j < _constraintsLength; j++){
                _weights.vec(edgeIndexStart + j).set(i, edgeIndex++);
            }
        }
        
        Vec.Writer capacitiesWriter = capacities.open();
        // Initialize graph and spanning tree.
        // always start with infinity _capacities
        for (long i = 0; i < _edgeSize; i++) {
            capacitiesWriter.set(i, Long.MAX_VALUE);
        }

        // find maximum value for capacity
        double maxCapacity = 3 * (_sumWeights > _maxAbsDemand ? _sumWeights : _maxAbsDemand);

        // fill max capacity from the leader node to all others _nodes
        for (long i = 0; i < _nodeSize; i++) {
            capacitiesWriter.set(i + _edgeSize, maxCapacity);
        }
        
        capacitiesWriter.close();
        
        this._capacitiesReader = capacities.new Reader();
        //this._additiveWeightsReader = additiveWeights.new Reader();
        this._demandsReader = demands.new Reader();

        this.tree = new SpanningTree(_nodeSize, _edgeSize, _constraintsLength);
        tree.init(_numberOfPoints, maxCapacity, demands);
    }

    /**
     * Get weight base on edge index from weights data or from additive weights.
     * @param edgeIndex
     * @return weight by edge index
     */
    public double getWeight(long edgeIndex) {
        long numberOfFrameWeights = this._numberOfPoints * this._constraintsLength;
        if (edgeIndex < numberOfFrameWeights) {
            int i = _weights.numCols() - 2 * _constraintsLength - 3 + (int)(edgeIndex % _constraintsLength);
            long j = Math.round(edgeIndex / _constraintsLength);
            return _weights.vec(i).at(j);
        }
        return 0;
    }

    /**
     * Get weight base on edge index from weights data or from additive weights.
     * @param edgeIndex
     * @return true if the weight at edge index is not zero
     */
    public boolean isNonZeroWeight(long edgeIndex) {
        if(_hasWeightsColumn) {
            long numberOfFrameWeights = this._numberOfPoints * this._constraintsLength;
            if (edgeIndex < numberOfFrameWeights) {
                long i = Math.round(edgeIndex / _constraintsLength);
                int j = _weights.numCols() - 1 - 2 * _constraintsLength - 3;
                return _weights.vec(j).at8(i) == 1;
            }
        }
        return true;
    }

    /**
     * Find edge which has the minimal reduced weight. 
     * @return edge index
     */
    public long findMinimalReducedWeight() {
        FindMinimalWeightTask t = new FindMinimalWeightTask(tree, _hasWeightsColumn, _constraintsLength).doAll(_weights);
        double minimalWeight = t.minimalWeight;
        long minimalIndex = t.minimalIndex;
        long additiveEdgesIndexStart = _weights.vec(0).length() * _constraintsLength;
        // Iterate over number of constraints, it is size K, MR task is not optimal here
        for(long i = additiveEdgesIndexStart; i < _edgeSize; i++){
            double tmpWeight = tree.reduceWeight(i, getWeight(i));
            boolean countValue = !_hasWeightsColumn || isNonZeroWeight(i);
            if (countValue && tmpWeight < minimalWeight) {
                minimalWeight = tmpWeight;
                minimalIndex = i;
            }
        }
        return minimalIndex;
    }

    /**
     * Find next optimal entering edge to find cycle.
     * @return index of the edge
     */
    public Edge findNextEnteringEdge() {
        // Check if continue
        if(!tree.areConstraintsSatisfied()) {
            long minimalIndex = findMinimalReducedWeight();
            if (tree.getFlowByEdgeIndex(minimalIndex) == 0) {
                return new Edge(minimalIndex, tree._sources.at8(minimalIndex), tree._targets.at8(minimalIndex));
            } else {
                return new Edge(minimalIndex, tree._targets.at8(minimalIndex), tree._sources.at8(minimalIndex));
            }
        }
        // if all constraints are satisfied, return null
        return null;
    }

    /**
     * Find cycle from the edge defined by source and target nodes to leader node and back.
     * @param edgeIndex
     * @param sourceIndex source node index
     * @param targetIndex target node index
     * @return cycle in spanning tree
     */
    public NodesEdgesObject getCycle(long edgeIndex, long sourceIndex, long targetIndex) {
        long ancestor = tree.findAncestor(sourceIndex, targetIndex);
        NodesEdgesObject resultPath = tree.getPath(sourceIndex, ancestor);
        resultPath.reverseNodes();
        resultPath.reverseEdges();
        if (resultPath.edgeSize() != 1 || resultPath.getEdge(0) != edgeIndex) {
            resultPath.addEdge(edgeIndex);
        }
        NodesEdgesObject resultPathBack = tree.getPath(targetIndex, ancestor);
        resultPathBack.removeLastNode();
        resultPath.addAllNodes(resultPathBack.getNodes());
        resultPath.addAllEdges(resultPathBack.getEdges());
        return resultPath;
    }

    /**
     * Find the leaving edge with minimal residual capacity.
     * @param cycle input cycle of edges and nodes to determine leaving edge
     * @return the edge with minimal residual capacity
     */
    public Edge getLeavingEdge(NodesEdgesObject cycle) {
        cycle.reverseNodes();
        cycle.reverseEdges();
        double minResidualCapacity = Double.MAX_VALUE;
        int minIndex = -1;
        for (int i = 0; i < cycle.edgeSize(); i++) {
            double tmpResidualCapacity = tree.getResidualCapacity(cycle.getEdge(i), cycle.getNode(i), _capacitiesReader.at(cycle.getEdge(i)));
            boolean countValue = !_hasWeightsColumn || isNonZeroWeight(cycle.getEdge(i));
            if (countValue && tmpResidualCapacity < minResidualCapacity) {
                minResidualCapacity = tmpResidualCapacity;
                minIndex = i;
            }
        }
        assert minIndex != -1;
        long nodeIndex = cycle.getNode(minIndex);
        long edgeIndex = cycle.getEdge(minIndex);
        return new Edge(edgeIndex, nodeIndex, nodeIndex == tree._sources.at8(edgeIndex) ? tree._targets.at8(edgeIndex) : tree._sources.at8(edgeIndex));
    }

    /**
     * Calculation minimal cost flow using pivot loop and spanning tree:
     * - Loop over all entering edges to find minimal cost flow in spanning tree.
     * - When edge is find edit spanning tree.
     * - If constraints are satisfied or no edge is found, stop.
     */
    public void calculateMinimalCostFlow() {
        Edge edge = findNextEnteringEdge();
        while (edge != null) {
            long enteringEdgeIndex = edge.getEdgeIndex();
            long enteringEdgeSourceIndex = edge.getSourceIndex();
            long enteringEdgeTargetIndex = edge.getTargetIndex();
            NodesEdgesObject cycle = getCycle(enteringEdgeIndex, enteringEdgeSourceIndex, enteringEdgeTargetIndex);
            Edge leavingEdge = getLeavingEdge(cycle);
            long leavingEdgeIndex = leavingEdge.getEdgeIndex();
            long leavingEdgeSourceIndex = leavingEdge.getSourceIndex();
            long leavingEdgeTargetIndex = leavingEdge.getTargetIndex();
            double residualCap = tree.getResidualCapacity(leavingEdgeIndex, leavingEdgeSourceIndex, _capacitiesReader.at(leavingEdgeIndex));
            if(residualCap != 0) {
                tree.augmentFlow(cycle, residualCap);
            }
            if (enteringEdgeIndex != leavingEdgeIndex) {
                if (leavingEdgeSourceIndex != tree._parents.at8(leavingEdgeTargetIndex)) {
                    long tmpS = leavingEdgeSourceIndex;
                    leavingEdgeSourceIndex = leavingEdgeTargetIndex;
                    leavingEdgeTargetIndex = tmpS;
                }
                if (cycle.indexOfEdge(enteringEdgeIndex) < cycle.indexOfEdge(leavingEdgeIndex)) {
                    long tmpP = enteringEdgeSourceIndex;
                    enteringEdgeSourceIndex = enteringEdgeTargetIndex;
                    enteringEdgeTargetIndex = tmpP;
                }
                tree.removeParentEdge(leavingEdgeSourceIndex, leavingEdgeTargetIndex);
                tree.makeRoot(enteringEdgeTargetIndex);
                tree.addEdge(enteringEdgeIndex, enteringEdgeSourceIndex, enteringEdgeTargetIndex);
                tree.updatePotentials(enteringEdgeIndex, enteringEdgeSourceIndex, enteringEdgeTargetIndex, getWeight(enteringEdgeIndex));
            }
            edge = findNextEnteringEdge();
        }
    }
    
    public void checkConstraintsCondition(int[] numberOfPointsInCluster){
        for(int i = 0; i<_constraintsLength; i++){
            assert numberOfPointsInCluster[i] >= _demandsReader.at8(_numberOfPoints+i) : String.format("Cluster %d has %d assigned points however should has assigned at least %d points.", i+1, numberOfPointsInCluster[i], _demandsReader.at8(_numberOfPoints+i));
        }
    }

    /**
     * Calculate minimal cost flow and based on flow assign cluster to all data points.
     * @return input data with new cluster assignments
     */
    public Frame assignClusters() {
        // run minimal cost flow calculation
        calculateMinimalCostFlow();
        
        // add flow columns to assign clusters
        _weights = _weights.add(new Frame(tree._edgeFlowDataPoints));
        int dataStopLength = _weights.numCols() - (_hasWeightsColumn ? 1 : 0) - 3 * _constraintsLength - 3;
        
        // assign cluster based on calculated flow
        AssignClusterTask task = new AssignClusterTask(_constraintsLength, _hasWeightsColumn, _weights.numCols());
        task.doAll(_weights);
        
        // check constraints are satisfied
        checkConstraintsCondition(task._numberOfPointsInCluster);
        
        // remove distances columns + edge indices columns
        for(int i = 0; i < 2 * _constraintsLength; i++) {
            _weights.remove(dataStopLength+(_hasWeightsColumn ? 1 : 0));
        }
        // remove flow columns 
        for(int i = 0; i < _constraintsLength; i++) {
            _weights.remove(_weights.numCols()-1);
        }
        return _weights;
    }
}

/**
 * Class to store structures for calculation of flow for minimal cost flow problem.
 */
class SpanningTree extends Iced<SpanningTree> {

    public long _nodeSize;
    public long _edgeSize;
    public int _secondLayerSize;
    public long _dataPointSize;
    
    public Vec[] _edgeFlowDataPoints;  //     [constraints size] nodeSize - secondLayerSize - 1 (number of data)
    public Vec _edgeFlowRest;          //     secondLayerSize size + node size
     
    public Vec _nodePotentials;        //     node size, long
    public Vec _parents;               //     node size + 1, integer
    public Vec _parentEdges;           //     node size + 1, integer
    public Vec _subtreeSize;           //     node size + 1, integer
    public Vec _nextDepthFirst;        //     node size + 1, integer
    public Vec _previousNodes;         //     node size + 1, integer
    public Vec _lastDescendants;       //     node size + 1, integer
    
    public Vec _sources;               //     edge size + node size
    public Vec _targets;               //     edge size + node size

    SpanningTree(long nodeSize, long edgeSize, int secondLayerSize){
        this._nodeSize = nodeSize;
        this._edgeSize = edgeSize;
        this._secondLayerSize = secondLayerSize;

        this._dataPointSize = nodeSize - secondLayerSize - 1;
        this._edgeFlowDataPoints = new Vec[secondLayerSize];
        for(int i=0; i < secondLayerSize; i++){
            this._edgeFlowDataPoints[i] = Vec.makeCon(0, _dataPointSize, Vec.T_NUM);
        }
        this._edgeFlowRest = Vec.makeCon(0, secondLayerSize + nodeSize, Vec.T_NUM);
        
        this._nodePotentials =  Vec.makeCon(0, nodeSize, Vec.T_NUM);
        this._parents =  Vec.makeCon(0, nodeSize+1, Vec.T_NUM);
        this._parentEdges =  Vec.makeCon(0, nodeSize+1, Vec.T_NUM);
        this._subtreeSize =  Vec.makeCon(1, nodeSize+1, Vec.T_NUM);
        this._nextDepthFirst =  Vec.makeCon(0, nodeSize+1, Vec.T_NUM);
        this._previousNodes =  Vec.makeCon(0, nodeSize+1, Vec.T_NUM);
        this._lastDescendants =  Vec.makeCon(0, nodeSize+1, Vec.T_NUM);
    }

    public void init(long numberOfPoints, double maxCapacity, Vec demands){
        _sources = Vec.makeCon(0, _edgeSize + _nodeSize, Vec.T_NUM);
        _targets = Vec.makeCon(0, _edgeSize + _nodeSize, Vec.T_NUM);
        for (long i = 0; i < _nodeSize; i++) {
            if (i < numberOfPoints) {
                for (int j = 0; j < _secondLayerSize; j++) {
                    _sources.set(i * _secondLayerSize + j, i);
                    _targets.set(i * _secondLayerSize + j, numberOfPoints + j);
                }
            } else {
                if (i < _nodeSize - 1) {
                    _sources.set(numberOfPoints* _secondLayerSize +i-numberOfPoints, i);
                    _targets.set(numberOfPoints* _secondLayerSize +i-numberOfPoints, _nodeSize - 1);
                }
            }
        }
        
        for (long i = 0; i < _nodeSize; i++) {
            long demand = demands.at8(i);
            if (demand >= 0) {
                _sources.set(_edgeSize + i, _nodeSize);
                _targets.set(_edgeSize + i, i);
            } else {
                _sources.set(_edgeSize + i, i);
                _targets.set(_edgeSize + i, _nodeSize);
            }
            if (i < _nodeSize - 1) {
                _nextDepthFirst.set(i, i + 1);
            }
            
            _edgeFlowRest.set(_secondLayerSize+i, Math.abs(demand));
            
            _nodePotentials.set(i, demand < 0 ? maxCapacity : -maxCapacity);
            _parents.set(i, _nodeSize);
            _parentEdges.set(i, i + _edgeSize);
            _previousNodes.set(i, i - 1);
            _lastDescendants.set(i, i);
        }
        
        _parents.set(_nodeSize, -1);
        _subtreeSize.set(_nodeSize, _nodeSize + 1);
        _nextDepthFirst.set(_nodeSize - 1, _nodeSize);
        _previousNodes.set(0, _nodeSize);
        _previousNodes.set(_nodeSize, _nodeSize - 1);
        _lastDescendants.set(_nodeSize, _nodeSize - 1);
    }

    /**
     * Check if the constraints are satisfied. 
     * If yes, the algorithm can continue as standard K-means and save time. Useful when constraints are small numbers
     * @return true if the constraints are satisfied
     */
    public boolean areConstraintsSatisfied() {
        Vec.Reader flowReader = _edgeFlowRest.new Reader();
        long length = flowReader.length();
        for(long i = 2; i < _secondLayerSize + 2; i++) {
            if(flowReader.at8(length - i) > 0) {
                return false;
            }
        }
        return true;
    }

    public long findAncestor(long sourceIndex, long targetIndex) {
        long subtreeSizeSource = _subtreeSize.at8(sourceIndex);
        long subtreeSizeTarget = _subtreeSize.at8(targetIndex);
        while (true) {
            while (subtreeSizeSource < subtreeSizeTarget) {
                sourceIndex = _parents.at8(sourceIndex);
                subtreeSizeSource = _subtreeSize.at8(sourceIndex);
            }
            while (subtreeSizeSource > subtreeSizeTarget) {
                targetIndex = _parents.at8(targetIndex);
                subtreeSizeTarget = _subtreeSize.at8(targetIndex);
            }
            if (subtreeSizeSource == subtreeSizeTarget) {
                if (sourceIndex !=targetIndex) {
                    sourceIndex = _parents.at8(sourceIndex);
                    subtreeSizeSource = _subtreeSize.at8(sourceIndex);
                    targetIndex = _parents.at8(targetIndex);
                    subtreeSizeTarget = _subtreeSize.at8(targetIndex);
                } else {
                    return sourceIndex;
                }
            }
        }
    }
    
    public long getFlowByEdgeIndex(long edgeIndex){
        if(edgeIndex < _dataPointSize * _secondLayerSize) {
            int i = (int)(edgeIndex % _secondLayerSize);
            long j = Math.round(edgeIndex / _secondLayerSize);
            return _edgeFlowDataPoints[i].at8(j);
        } else {
            return _edgeFlowRest.at8(edgeIndex-_dataPointSize * _secondLayerSize);
        }
    }

    public void setFlowByEdgeIndex(long edgeIndex, long value){
        if(edgeIndex < _dataPointSize * _secondLayerSize) {
            int i = (int)(edgeIndex % _secondLayerSize);
            long j = Math.round(edgeIndex / _secondLayerSize);
            _edgeFlowDataPoints[i].set(j, value);
        } else {
             _edgeFlowRest.set(edgeIndex - _dataPointSize * _secondLayerSize, value);
        }
    }
    

    public double reduceWeight(long edgeIndex, double weight) {
        double newWeight = weight - _nodePotentials.at(_sources.at8(edgeIndex)) + _nodePotentials.at(_targets.at8(edgeIndex));
        return getFlowByEdgeIndex(edgeIndex) == 0 ? newWeight : - newWeight;
    }

    public NodesEdgesObject getPath(long node, long ancestor) {
        NodesEdgesObject result = new NodesEdgesObject();
        result.addNode(node);
        while (node != ancestor) {
            result.addEdge(_parentEdges.at8(node));
            node = _parents.at8(node);
            result.addNode(node);
        }
        return result;
    }

    public double getResidualCapacity(long edgeIndex, long nodeIndex, double capacity) {
        long flow = getFlowByEdgeIndex(edgeIndex);
        return nodeIndex == _sources.at8(edgeIndex) ? capacity - flow : flow;
    }

    public void augmentFlow(NodesEdgesObject nodesEdges, double flow) {
        for (int i = 0; i < nodesEdges.edgeSize(); i++) {
            long edge = nodesEdges.getEdge(i);
            long node = nodesEdges.getNode(i);
            long edgeFlow = getFlowByEdgeIndex(edge);
            if (node == _sources.at8(edge)) {
                setFlowByEdgeIndex(edge, edgeFlow + (int)flow);
            } else {
                setFlowByEdgeIndex(edge, edgeFlow - (int)flow);
            }
        }
    }

    public void removeParentEdge(long sourceIndex, long targetIndex) {
        long subtreeSizeTarget = _subtreeSize.at8(targetIndex);
        long previousTargetIndex = _previousNodes.at8(targetIndex);
        long lastTargetIndex = _lastDescendants.at8(targetIndex);
        long nextTargetIndex = _nextDepthFirst.at8(lastTargetIndex);

        _parents.set(targetIndex, -1);
        _parentEdges.set(targetIndex, -1);
        _nextDepthFirst.set(previousTargetIndex, nextTargetIndex);
        _previousNodes.set(nextTargetIndex, previousTargetIndex);
        _nextDepthFirst.set(lastTargetIndex, targetIndex);
        _previousNodes.set(targetIndex, lastTargetIndex);
        while (sourceIndex != -1) {
            _subtreeSize.set(sourceIndex, _subtreeSize.at8(sourceIndex) - subtreeSizeTarget);
            if (lastTargetIndex == _lastDescendants.at8(sourceIndex)) {
                _lastDescendants.set(sourceIndex, previousTargetIndex);
            }
            sourceIndex = _parents.at8(sourceIndex);
        }
    }

    public void makeRoot(long nodeIndex) {
        ArrayList<Long> ancestors = new ArrayList<>();
        while (nodeIndex != -1) {
            ancestors.add(nodeIndex);
            nodeIndex = _parents.at8(nodeIndex);
        }
        Collections.reverse(ancestors);
        for (int i = 0; i < ancestors.size() - 1; i++) {
            long sourceIndex = ancestors.get(i);
            long targetIndex = ancestors.get(i + 1);
            long subtreeSizeSource = _subtreeSize.at8(sourceIndex);
            long lastSourceIndex = _lastDescendants.at8(sourceIndex);
            long prevTargetIndex = _previousNodes.at8(targetIndex);
            long lastTargetIndex = _lastDescendants.at8(targetIndex);
            long nextTargetIndex = _nextDepthFirst.at8(lastTargetIndex);

            _parents.set(sourceIndex, targetIndex);
            _parents.set(targetIndex, -1);
            _parentEdges.set(sourceIndex, _parentEdges.at8(targetIndex));
            _parentEdges.set(targetIndex, -1);
            _subtreeSize.set(sourceIndex, subtreeSizeSource - _subtreeSize.at8(targetIndex));
            _subtreeSize.set(targetIndex, subtreeSizeSource);

            _nextDepthFirst.set(prevTargetIndex, nextTargetIndex);
            _previousNodes.set(nextTargetIndex, prevTargetIndex);
            _nextDepthFirst.set(lastTargetIndex, targetIndex);
            _previousNodes.set(targetIndex, lastTargetIndex);

            if (lastSourceIndex == lastTargetIndex) {
                _lastDescendants.set(sourceIndex, prevTargetIndex);
                lastSourceIndex = prevTargetIndex;
            }
            _previousNodes.set(sourceIndex, lastTargetIndex);
            _nextDepthFirst.set(lastTargetIndex, sourceIndex);
            _nextDepthFirst.set(lastSourceIndex, targetIndex);
            _previousNodes.set(targetIndex, lastSourceIndex);
            _lastDescendants.set(targetIndex, lastSourceIndex);
        }
    }

    public void addEdge(long edgeIndex, long sourceIndex, long targetIndex) {
        long lastSourceIndex = _lastDescendants.at8(sourceIndex);
        long nextSourceIndex = _nextDepthFirst.at8(lastSourceIndex);
        long subtreeSizeTarget = _subtreeSize.at8(targetIndex);
        long lastTargetIndex = _lastDescendants.at8(targetIndex);

        _parents.set(targetIndex, sourceIndex);
        _parentEdges.set(targetIndex, edgeIndex);

        _nextDepthFirst.set(lastSourceIndex, targetIndex);
        _previousNodes.set(targetIndex, lastSourceIndex);
        _previousNodes.set(nextSourceIndex, lastTargetIndex);
        _nextDepthFirst.set(lastTargetIndex, nextSourceIndex);
        
        while (sourceIndex != -1) {
            _subtreeSize.set(sourceIndex, _subtreeSize.at8(sourceIndex) + subtreeSizeTarget);
            if (lastSourceIndex == _lastDescendants.at8(sourceIndex)) {
                _lastDescendants.set(sourceIndex, lastTargetIndex);
            }
            sourceIndex = _parents.at8(sourceIndex);
        }
    }


    public void updatePotentials(long edgeIndex, long sourceIndex, long targetIndex, double weight) {
        double potential;
        if (targetIndex == _targets.at8(edgeIndex)) {
            potential = _nodePotentials.at(sourceIndex) - weight - _nodePotentials.at(targetIndex);
        } else {
            potential = _nodePotentials.at(sourceIndex) + weight - _nodePotentials.at(targetIndex);
        }
        _nodePotentials.set(targetIndex, _nodePotentials.at(targetIndex) + potential);
        long last = _lastDescendants.at8(targetIndex);
        while (targetIndex != last) {
            targetIndex = _nextDepthFirst.at8(targetIndex);
            _nodePotentials.set(targetIndex, _nodePotentials.at(targetIndex) + potential);
        }
    }
}

/**
 * Helper class to store edges in Spanning tree net
 */
class Edge {

    private long _edgeIndex;
    private long _sourceIndex;
    private long _targetIndex;

    public Edge(long edgeIndex, long sourceIndex, long targetIndex) {
        this._edgeIndex = edgeIndex;
        this._sourceIndex = sourceIndex;
        this._targetIndex = targetIndex;
    }

    public long getEdgeIndex() {
        return _edgeIndex;
    }

    public long getSourceIndex() {
        return _sourceIndex;
    }

    public long getTargetIndex() {
        return _targetIndex;
    }

    @Override
    public String toString() {
        return _edgeIndex+" "+_sourceIndex+" "+_targetIndex;
    }
}

/**
 * Helper class to store edges and nodes of one cycle in Spanning tree net
 */
class NodesEdgesObject {

    private ArrayList<Long> _nodes;
    private ArrayList<Long> _edges;

    public NodesEdgesObject() {
        this._nodes = new ArrayList<>();
        this._edges = new ArrayList<>();
    }

    public void addNode(long node){
        _nodes.add(node);
    }

    public void removeLastNode(){
        _nodes.remove(_nodes.size()-1);
    }

    public long getNode(int index){
        return _nodes.get(index);
    }

    public ArrayList<Long> getNodes() {
        return _nodes;
    }

    public void addEdge(long edge){
        _edges.add(edge);
    }

    public long getEdge(int index){
        return _edges.get(index);
    }

    public ArrayList<Long> getEdges() {
        return _edges;
    }

    public int edgeSize(){
        return _edges.size();
    }

    public int indexOfEdge(long value){
        return _edges.indexOf(value);
    }
    public void reverseNodes(){
        Collections.reverse(_nodes);
    }

    public  void reverseEdges(){
        Collections.reverse(_edges);
    }

    public void addAllNodes(ArrayList<Long> newNodes){
        _nodes.addAll(newNodes);
    }

    public void addAllEdges(ArrayList<Long> newEdges){
        _edges.addAll(newEdges);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NEO: nodes: ");
        for (long i: _nodes) {
            sb.append(i+" ");
        }
        sb.append("edges: ");
        for (long i: _edges) {
            sb.append(i+" ");
        }
        sb.deleteCharAt(sb.length()-1);
        return sb.toString();
    }
}


/**
 * Map Reduce task to find minimal reduced weight (distance).
 */
class FindMinimalWeightTask extends MRTask<FindMinimalWeightTask> {
    // IN
    private SpanningTree _tree;
    private boolean _hasWeightsColumn;
    private int _constraintsLength;

    //OUT
    double minimalWeight = Double.MAX_VALUE;
    long minimalIndex = -1;

    FindMinimalWeightTask(SpanningTree tree, boolean hasWeightsColumn, int constraintsLength) {
        _tree = tree;
        _hasWeightsColumn = hasWeightsColumn;
        _constraintsLength = constraintsLength;
    }

    @Override
    public void map(Chunk[] cs) {
        int startDistancesIndex = cs.length - 2 * _constraintsLength - 3;
        int startEdgeIndex = cs.length - 3 - _constraintsLength;
        for (int i = 0; i < cs[0]._len; i++) {
            for (int j = 0; j < _constraintsLength; j++) {
                double weight = cs[startDistancesIndex + j].atd(i);
                long edgeIndex = cs[startEdgeIndex + j].at8(i);
                double tmpWeight = _tree.reduceWeight(edgeIndex, weight);
                boolean countValue = !_hasWeightsColumn || cs[startDistancesIndex-1].at8(i) == 1;
                if (countValue && tmpWeight < minimalWeight) {
                    minimalWeight = tmpWeight;
                    minimalIndex = edgeIndex;
                }
            }
        }
    }

    @Override
    public void reduce(FindMinimalWeightTask mrt) {
        if (mrt.minimalWeight < minimalWeight) {
            minimalIndex = mrt.minimalIndex;
            minimalWeight = mrt.minimalWeight;
        }
    }
}

/**
 * Map Reduce task to assign cluster index based on calculated flow.
 * If no cluster assigned - assign cluster by minimal distance.
 * Return number of points in each cluster and changed input frame based on new cluster assignment.
 */
class AssignClusterTask extends MRTask<AssignClusterTask> {

    // IN
    private int _constraintsLength;
    private boolean _hasWeightsColumn;

    private int _weightIndex;
    private int _distanceIndexStart;
    private int _flowIndexStart;
    private int _oldAssignmentIndex;
    private int _newAssignmentIndex;
    private int _distanceAssignmentIndex;
    private int _dataStopIndex;
    
    // OUT
    int[] _numberOfPointsInCluster;
    // changed input chunks

    AssignClusterTask(int constraintsLength, boolean hasWeightsColumn, int numCols){
        // Input data structure should be: 
        //  - data points (number of columns from training dataset)
        //  - weight (1 column if CV is enabled) 
        //  - distances from data points to each cluster (k columns)
        //  - edge indices (k columns of columns, not useful here)
        //  - result distance (1 column, if the cluster is assigned there is distance to this cluster)
        //  - old assignment (1 column, assignment from the previous iteration)
        //  - new assignment (1 column, assignment form the current iteration)
        //  - flow (k columns, calculated assignment from the MCF algorithm)
        // Based on this structure indices are calculated and used
        _constraintsLength = constraintsLength;
        _hasWeightsColumn = hasWeightsColumn;
        _distanceAssignmentIndex = numCols - 3 - constraintsLength;
        _oldAssignmentIndex = numCols - 2 - constraintsLength;
        _newAssignmentIndex = numCols - 1 - constraintsLength;
        _dataStopIndex = numCols - (_hasWeightsColumn ? 1 : 0) - 3 * _constraintsLength - 3;
        _weightIndex = _dataStopIndex;
        _distanceIndexStart = _dataStopIndex + (_hasWeightsColumn ? 1 : 0);
        _flowIndexStart = numCols - constraintsLength;
    }
    
    public void assignCluster(Chunk[] cs, int row, int clusterIndex){
        // old assignment
        cs[_oldAssignmentIndex].set(row, cs[_newAssignmentIndex].at8(row));
        // new assignment
        cs[_newAssignmentIndex].set(row, clusterIndex);
        // distances
        cs[_distanceAssignmentIndex].set(row, cs[_dataStopIndex + (_hasWeightsColumn ? 1 : 0)  + clusterIndex].atd(row));
        _numberOfPointsInCluster[clusterIndex]++;
    }

    @Override
    public void map(Chunk[] cs) {
        _numberOfPointsInCluster = new int[_constraintsLength];
        for (int i = 0; i < cs[0].len(); i++) {
            if (!_hasWeightsColumn || cs[_weightIndex].at8(i) == 1) {
                // CV is not enabled or weight is 1
                boolean assigned = false;
                for (int j = 0; j < _constraintsLength; j++) {
                    if (cs[_flowIndexStart + j].at8(i) == 1) {
                        // data point has assignment from MCF algorithm
                        assignCluster(cs, i, j);
                        assigned = true;
                        break;
                    }
                }
                if(!assigned){
                    // data point has no assignment from MCF -> min distance is used
                    double minDistance = cs[_distanceIndexStart].atd(i);
                    int minIndex = 0;
                    for (int j = 1; j < _constraintsLength; j++) {
                        double tmpDistance = cs[_distanceIndexStart + j].atd(i);
                        if(minDistance > tmpDistance){
                            minDistance = tmpDistance;
                            minIndex = j;
                        }
                    }
                    assignCluster(cs, i, minIndex);
                }
            }
        }
    }

    @Override
    public void reduce(AssignClusterTask mrt) {
        ArrayUtils.add(this._numberOfPointsInCluster, mrt._numberOfPointsInCluster);
    }
}

