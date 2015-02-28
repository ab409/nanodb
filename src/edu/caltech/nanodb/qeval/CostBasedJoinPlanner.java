package edu.caltech.nanodb.qeval;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.caltech.nanodb.commands.FromClause;
import edu.caltech.nanodb.commands.SelectClause;
import edu.caltech.nanodb.commands.SelectValue;
import edu.caltech.nanodb.expressions.AggregateReplacementProcessor;
import edu.caltech.nanodb.expressions.BooleanOperator;
import edu.caltech.nanodb.expressions.ColumnValue;
import edu.caltech.nanodb.expressions.Expression;
import edu.caltech.nanodb.expressions.OrderByExpression;
import edu.caltech.nanodb.expressions.PredicateUtils;
import edu.caltech.nanodb.plans.FileScanNode;
import edu.caltech.nanodb.plans.HashedGroupAggregateNode;
import edu.caltech.nanodb.plans.NestedLoopsJoinNode;
import edu.caltech.nanodb.plans.PlanNode;
import edu.caltech.nanodb.plans.PlanUtils;
import edu.caltech.nanodb.plans.ProjectNode;
import edu.caltech.nanodb.plans.RenameNode;
import edu.caltech.nanodb.plans.SelectNode;
import edu.caltech.nanodb.plans.SimpleFilterNode;
import edu.caltech.nanodb.plans.SortNode;
import edu.caltech.nanodb.relations.ColumnInfo;
import edu.caltech.nanodb.relations.JoinType;
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.TableInfo;
import edu.caltech.nanodb.storage.StorageManager;


/**
 * This planner implementation uses dynamic programming to devise an optimal
 * join strategy for the query.  As always, queries are optimized in units of
 * <tt>SELECT</tt>-<tt>FROM</tt>-<tt>WHERE</tt> subqueries; optimizations
 * don't currently span multiple subqueries.
 */
public class CostBasedJoinPlanner implements Planner {

    /** A logging object for reporting anything interesting that happens. */
    private static Logger logger = Logger.getLogger(CostBasedJoinPlanner.class);


    private StorageManager storageManager;


    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * This helper class is used to keep track of one "join component" in the
     * dynamic programming algorithm.  A join component is simply a query plan
     * for joining one or more leaves of the query.
     * <p>
     * In this context, a "leaf" may either be a base table or a subquery in
     * the <tt>FROM</tt>-clause of the query.  However, the planner will
     * attempt to push conjuncts down the plan as far as possible, so even if
     * a leaf is a base table, the plan may be a bit more complex than just a
     * single file-scan.
     */
    private static class JoinComponent {
        /**
         * This is the join plan itself, that joins together all leaves
         * specified in the {@link #leavesUsed} field.
         */
        public PlanNode joinPlan;

        /**
         * This field specifies the collection of leaf-plans that are joined by
         * the plan in this join-component.
         */
        public HashSet<PlanNode> leavesUsed;

        /**
         * This field specifies the collection of all conjuncts use by this join
         * plan.  It allows us to easily determine what join conjuncts still
         * remain to be incorporated into the query.
         */
        public HashSet<Expression> conjunctsUsed;

        AggregateReplacementProcessor processor = new AggregateReplacementProcessor();

        /**
         * Constructs a new instance for a <em>leaf node</em>.  It should not
         * be used for join-plans that join together two or more leaves.  This
         * constructor simply adds the leaf-plan into the {@link #leavesUsed}
         * collection.
         *
         * @param leafPlan the query plan for this leaf of the query.
         *
         * @param conjunctsUsed the set of conjuncts used by the leaf plan.
         *        This may be an empty set if no conjuncts apply solely to
         *        this leaf, or it may be nonempty if some conjuncts apply
         *        solely to this leaf.
         */
        public JoinComponent(PlanNode leafPlan, HashSet<Expression> conjunctsUsed) {
            leavesUsed = new HashSet<PlanNode>();
            leavesUsed.add(leafPlan);

            joinPlan = leafPlan;

            this.conjunctsUsed = conjunctsUsed;
        }

        /**
         * Constructs a new instance for a <em>non-leaf node</em>.  It should
         * not be used for leaf plans!
         *
         * @param joinPlan the query plan that joins together all leaves
         *        specified in the <tt>leavesUsed</tt> argument.
         *
         * @param leavesUsed the set of two or more leaf plans that are joined
         *        together by the join plan.
         *
         * @param conjunctsUsed the set of conjuncts used by the join plan.
         *        Obviously, it is expected that all conjuncts specified here
         *        can actually be evaluated against the join plan.
         */
        public JoinComponent(PlanNode joinPlan, HashSet<PlanNode> leavesUsed,
                             HashSet<Expression> conjunctsUsed) {
            this.joinPlan = joinPlan;
            this.leavesUsed = leavesUsed;
            this.conjunctsUsed = conjunctsUsed;
        }
    }

    /**
     * Returns a projection node for the given select statement.
     * @param child The child of the resultant node.
     * @param selClause
     * @return The projection node.
     */
    private PlanNode planProjectClause(PlanNode child, SelectClause selClause) {
        PlanNode projNode = child;

        List<SelectValue> finalSchema = selClause.getFromClause().getPreparedSelectValues();
        logger.debug(String.format("preparedSchema: %s", selClause.getFromClause().getPreparedSchema()));
        logger.debug(String.format("preparedSelectedValues: %s", selClause.getFromClause().getPreparedSelectValues()));
        if (finalSchema != null) {
            projNode = new ProjectNode(child, finalSchema);
        }

        if (selClause.isTrivialProject()) {
            // finalSchema is null when we're doing an ON join.
            // This is handling a trivial projection of an inner join of two tables
            if (finalSchema == null && selClause.getFromClause().isJoinExpr()) {
                Schema schema = selClause.getFromClause().getPreparedSchema();
                List<SelectValue> selValues = new ArrayList<SelectValue>();
                // constructing the selectValues from the schema, so that the produced columns
                // are in the same order as in the query (the optimized plan might have reordered them)
                for (ColumnInfo columnInfo: schema.getColumnInfos()) {
                    SelectValue sv = new SelectValue(new ColumnValue(columnInfo.getColumnName()), null);
                    selValues.add(sv);
                }
                projNode = new ProjectNode(child, selValues);
                return projNode;
            }
            else {
                return projNode;
            }
        }
        List<SelectValue> columns = selClause.getSelectValues();
        projNode = new ProjectNode(child, columns);
        return projNode;
    }

    /**
     * Returns an order-by node corresponding for the given select statement.
     * @param child The child of the resultant node.
     * @param selClause
     * @return The order-by node.
     */
    private PlanNode planOrderByClause(PlanNode child, SelectClause selClause) {
        List<OrderByExpression> orderExpressions = selClause.getOrderByExprs();
        if (!orderExpressions.isEmpty()) {
            return new SortNode(child, orderExpressions);
        }
        return child;
    }

    private void traverseAggregateFunctions(SelectClause selClause, AggregateReplacementProcessor processor) {
        for (SelectValue sv : selClause.getSelectValues()) {
            if (!sv.isExpression())
                continue;
            Expression e = sv.getExpression().traverse(processor);
            sv.setExpression(e);
        }

        // Update the having expression
        if (selClause.getHavingExpr() != null) {
            Expression e = selClause.getHavingExpr().traverse(processor);
            selClause.setHavingExpr(e);
        }

        // Make sure there are no aggregate functions in the where / from clauses.
        if (selClause.getWhereExpr() != null) {
            processor.setErrorMessage("Aggregate functions in WHERE clauses are not allowed");
            selClause.getWhereExpr().traverse(processor);
        }
        if (selClause.getFromClause() != null && selClause.getFromClause().getClauseType() ==
                FromClause.ClauseType.JOIN_EXPR && selClause.getFromClause().getOnExpression() != null) {
            processor.setErrorMessage("Aggregate functions in ON clauses are not allowed");
            selClause.getFromClause().getOnExpression().traverse(processor);
        }
    }

    /**
     * Returns a plan node for the grouping/aggregation part of the select statement.
     *
     * Aggregation function calls are replaced with column references by AggregateReplacementProcessor. These,
     * in turn, are employed during evaluation.
     *
     * @param child The child of the resultant node.
     * @param selClause
     * @return The resultant node.
     */
    private PlanNode planGroupingAggregation(PlanNode child, SelectClause selClause, AggregateReplacementProcessor processor) {

        List<Expression> groupByExprs = selClause.getGroupByExprs();

        if (processor.getGroupAggregates().isEmpty() && groupByExprs.isEmpty()) {
            return child;
        }

        HashedGroupAggregateNode hashNode = new HashedGroupAggregateNode(child, groupByExprs, processor.getGroupAggregates());
        return hashNode;
    }

    /**
     * Returns the root of a plan tree suitable for executing the specified
     * query.
     *
     * @param selClause an object describing the query to be performed
     *
     * @return a plan tree for executing the specified query
     *
     * @throws java.io.IOException if an IO error occurs when the planner attempts to
     *         load schema and indexing information.
     */
    public PlanNode makePlan(SelectClause selClause,
        List<SelectClause> enclosingSelects) throws IOException {

        // We want to take a simple SELECT a, b, ... FROM A, B, ... WHERE ...
        // and turn it into a tree of plan nodes.

        FromClause fromClause = selClause.getFromClause();

        if (fromClause == null) {
            List<SelectValue> columns = selClause.getSelectValues();
            ProjectNode projNode = new ProjectNode(null, columns);
            projNode.prepare();
            return projNode;
        }
        else {
            fromClause.prepare(storageManager.getTableManager());
        }

        //
        // These are the general steps to follow, although this must be
        // modified to also support grouping and aggregates:
        //
        // 1)  Pull out the top-level conjuncts from the WHERE and HAVING
        //     clauses on the query, since we will handle them in special ways
        //     if we have outer joins.
        //
        // 2)  Create an optimal join plan from the top-level from-clause and
        //     the top-level conjuncts.
        //
        // 3)  If there are any unused conjuncts, determine how to handle them.
        //
        // 4)  Create a project plan-node if necessary.
        //
        // 5)  Handle other situations such as ORDER BY, or LIMIT/OFFSET if
        //     you have implemented this plan-node.

        HashSet<Expression> conjuncts = new HashSet<Expression>();
        if (selClause.getWhereExpr() != null) {
            PredicateUtils.collectConjuncts(selClause.getWhereExpr(), conjuncts);
        }
        if (selClause.getHavingExpr() != null) {
            PredicateUtils.collectConjuncts(selClause.getHavingExpr(), conjuncts);
        }
        PlanNode res = null;

        AggregateReplacementProcessor processor = new AggregateReplacementProcessor();

        traverseAggregateFunctions(selClause, processor);
        JoinComponent joinComponent = makeJoinPlan(selClause.getFromClause(), conjuncts);
        conjuncts.removeAll(joinComponent.conjunctsUsed);

        res = planGroupingAggregation(joinComponent.joinPlan, selClause, processor);
        logger.debug("    Result plan:  " +
                PlanNode.printNodeTreeToString(res, true));
        
        if (!conjuncts.isEmpty()) {
            Expression unusedExpr = PredicateUtils.makePredicate(conjuncts);
            res = new SimpleFilterNode(res, unusedExpr);
        }

        res = planProjectClause(res, selClause);
        logger.debug("    Result plan:  " +
                PlanNode.printNodeTreeToString(res, true));

        res = planOrderByClause(res, selClause);
        logger.debug("    Result plan:  " +
                PlanNode.printNodeTreeToString(res, true));


        res.prepare();
        return res;
    }


    /**
     * Given the top-level {@code FromClause} for a SELECT-FROM-WHERE block,
     * this helper generates an optimal join plan for the {@code FromClause}.
     *
     * @param fromClause the top-level {@code FromClause} of a
     *        SELECT-FROM-WHERE block.
     * @param extraConjuncts any extra conjuncts (e.g. from the WHERE clause,
     *        or HAVING clause)
     * @return a {@code JoinComponent} object that represents the optimal plan
     *         corresponding to the FROM-clause
     * @throws IOException if an IO error occurs during planning.
     */
    private JoinComponent makeJoinPlan(FromClause fromClause,
        Collection<Expression> extraConjuncts) throws IOException {

        // These variables receive the leaf-clauses and join conjuncts found
        // from scanning the sub-clauses.  Initially, we put the extra conjuncts
        // into the collection of conjuncts.
        HashSet<Expression> conjuncts = new HashSet<Expression>();
        ArrayList<FromClause> leafFromClauses = new ArrayList<FromClause>();

        collectDetails(fromClause, conjuncts, leafFromClauses);

        logger.debug("Making join-plan for " + fromClause);
        logger.debug("    Collected conjuncts:  " + conjuncts);
        logger.debug("    Collected FROM-clauses:  " + leafFromClauses);
        logger.debug("    Extra conjuncts:  " + extraConjuncts);

        if (extraConjuncts != null)
            conjuncts.addAll(extraConjuncts);

        // Make a read-only set of the input conjuncts, to avoid bugs due to
        // unintended side-effects.
        Set<Expression> roConjuncts = Collections.unmodifiableSet(conjuncts);

        // Create a subplan for every single leaf FROM-clause, and prepare the
        // leaf-plan.

        logger.debug("Generating plans for all leaves");
        ArrayList<JoinComponent> leafComponents = generateLeafJoinComponents(
            leafFromClauses, roConjuncts);

        // Print out the results, for debugging purposes.
        if (logger.isDebugEnabled()) {
            for (JoinComponent leaf : leafComponents) {
                logger.debug("    Leaf plan:  " +
                    PlanNode.printNodeTreeToString(leaf.joinPlan, true));
            }
        }

        // Build up the full query-plan using a dynamic programming approach.

        JoinComponent optimalJoin =
            generateOptimalJoin(leafComponents, roConjuncts);

        PlanNode plan = optimalJoin.joinPlan;
        logger.info("Optimal join plan generated:\n" +
            PlanNode.printNodeTreeToString(plan, true));

        logger.debug("extraConjuncts:\n" +
                extraConjuncts);

        return optimalJoin;
    }


    /**
     * This helper method pulls the essential details for join optimization
     * out of a <tt>FROM</tt> clause.
     *
     * If the fromClause is a leaf, add it to leafFromClauses. Otherwise it is 
     * a join expression -- extract the prepared join expression, then 
     * recursively call collectDetails on left and right fromClauses.
     *
     * @param fromClause the from-clause to collect details from
     *
     * @param conjuncts the collection to add all conjuncts to
     *
     * @param leafFromClauses the collection to add all leaf from-clauses to
     */
    private void collectDetails(FromClause fromClause,
        HashSet<Expression> conjuncts, ArrayList<FromClause> leafFromClauses) {
        if (fromClause.isBaseTable() || fromClause.isDerivedTable() || fromClause.isOuterJoin()) {
            leafFromClauses.add(fromClause);
        }
        else if (fromClause.isJoinExpr()) {
            FromClause fromLeft, fromRight;
            fromLeft = fromClause.getLeftChild();
            fromRight = fromClause.getRightChild();
            
            HashSet<Expression> newConjuncts = new HashSet<Expression>();
            
            if (fromClause.getConditionType() != null) {
                Expression joinExpr = fromClause.getPreparedJoinExpr();
                PredicateUtils.collectConjuncts(joinExpr, newConjuncts);
            }    
            conjuncts.addAll(newConjuncts);
            collectDetails(fromLeft, conjuncts, leafFromClauses);
            collectDetails(fromRight, conjuncts, leafFromClauses);
        }
        else {
            throw new RuntimeException("Bad from clause.");
        }
    }

    /**
     * This helper method performs the first step of the dynamic programming
     * process to generate an optimal join plan, by generating a plan for every
     * leaf from-clause identified from analyzing the query.  Leaf plans are
     * usually very simple; they are built either from base-tables or
     * <tt>SELECT</tt> subqueries.  The most complex detail is that any
     * conjuncts in the query that can be evaluated solely against a particular
     * leaf plan-node will be associated with the plan node.  <em>This is a
     * heuristic</em> that usually produces good plans (and certainly will for
     * the current state of the database), but could easily interfere with
     * indexes or other plan optimizations.
     *
     * @param leafFromClauses the collection of from-clauses found in the query
     *
     * @param conjuncts the collection of conjuncts that can be applied at this
     *                  level
     *
     * @return a collection of {@link JoinComponent} object containing the plans
     *         and other details for each leaf from-clause
     *
     * @throws IOException if a particular database table couldn't be opened or
     *         schema loaded, for some reason
     */
    private ArrayList<JoinComponent> generateLeafJoinComponents(
        Collection<FromClause> leafFromClauses, Collection<Expression> conjuncts)
        throws IOException {

        // Create a subplan for every single leaf FROM-clause, and prepare the
        // leaf-plan.
        ArrayList<JoinComponent> leafComponents = new ArrayList<JoinComponent>();
        for (FromClause leafClause : leafFromClauses) {
            HashSet<Expression> leafConjuncts = new HashSet<Expression>();

            PlanNode leafPlan =
                makeLeafPlan(leafClause, conjuncts, leafConjuncts);

            JoinComponent leaf = new JoinComponent(leafPlan, leafConjuncts);
            leafComponents.add(leaf);
        }

        return leafComponents;
    }


    /**
     * Constructs a plan tree for evaluating the specified from-clause.
     * The function handles plan construction for the three types of leaf 
     * nodes -- base tables, select subqueries, and outer joins. For base 
     * tables, we find applicable conjuncts and pass them to makeSimpleSelect().
     * 
     * For select subqueries, we recursively call the makePlan function with 
     * the subquery.
     * 
     * For outer joins, we find the schema of the left or right clause 
     * depending on whether the join is left or right outer join, respectively.
     * We find the conjuncts that apply to this schema then pass these to
     * the recursive calls of makeJoinPlan for the left and right clauses. We
     * then apply a NestedLoopsJoinNode to combine the JoinComponents returned
     * for the left and right clauses to perform the correct outer join.
     * 
     * For base tables and select subqueries, we apply a rename node if 
     * necessary. Finally, apply any remaining conjuncts to the plan node.
     *
     * @param fromClause the select nodes that need to be joined.
     *
     * @param conjuncts additional conjuncts that can be applied when
     *        constructing the from-clause plan.
     *
     * @param leafConjuncts this is an output-parameter.  Any conjuncts applied
     *        in this plan from the <tt>conjuncts</tt> collection should be added
     *        to this out-param.
     *
     * @return a plan tree for evaluating the specified from-clause
     *
     * @throws IOException if an IO error occurs when the planner attempts to
     *         load schema and indexing information.
     *
     * @throws IllegalArgumentException if the specified from-clause is a join
     *         expression that isn't an outer join, or has some other
     *         unrecognized type.
     */
    private PlanNode makeLeafPlan(FromClause fromClause,
        Collection<Expression> conjuncts, HashSet<Expression> leafConjuncts)
        throws IOException {
    	
    	HashSet<Expression> conjunctsCopy = new HashSet<Expression>(conjuncts);
        
        PlanNode node = null;
        Schema schema;
        
        switch (fromClause.getClauseType()) {
        case BASE_TABLE:
            HashSet<Expression> baseExprs = new HashSet<Expression>();
        	schema = fromClause.getPreparedSchema();
            PredicateUtils.findExprsUsingSchemas(conjunctsCopy, true, baseExprs, schema);
            if (!baseExprs.isEmpty()) 
            	leafConjuncts.addAll(baseExprs);
            Expression expr = PredicateUtils.makePredicate(baseExprs);
            
            node = makeSimpleSelect(fromClause.getTableName(), expr, null);
            if (fromClause.isRenamed())
            	node = new RenameNode(node, fromClause.getResultName());
            break;
        case SELECT_SUBQUERY:
            node = makePlan(fromClause.getSelectClause(), null);
            if (fromClause.isRenamed()) 
            	node = new RenameNode(node, fromClause.getResultName());
            break;
        case JOIN_EXPR:
            assert fromClause.hasOuterJoinOnLeft() || 
                fromClause.hasOuterJoinOnRight() : "Join is not outer!";
            FromClause fromLeft = fromClause.getLeftChild();
            FromClause fromRight = fromClause.getRightChild();
            HashSet<Expression> extraConjuncts = new HashSet<Expression>();
            schema = null;
            
            if (fromClause.hasOuterJoinOnLeft()) 
                schema = fromLeft.getPreparedSchema();
            else if (fromClause.hasOuterJoinOnRight()) 
                schema = fromRight.getPreparedSchema();
            
            // Only pass in conjuncts that correspond to the child from-clause
            // that is outer joined (i.e. left or right), per the equivalence
            // rule sigma_theta1(E1 LOJ E2) = sigma_theta1(E1) LOJ E2, where 
            // theta1 refers only to attributes in E1. 
            PredicateUtils.findExprsUsingSchemas(conjunctsCopy, true, extraConjuncts, schema);
            JoinComponent left = makeJoinPlan(fromLeft, 
            		fromClause.hasOuterJoinOnLeft() ? extraConjuncts : null);
            JoinComponent right = makeJoinPlan(fromRight, 
            		fromClause.hasOuterJoinOnRight() ? extraConjuncts : null);
            if (!left.conjunctsUsed.isEmpty())
            	leafConjuncts.addAll(left.conjunctsUsed);
            if (!right.conjunctsUsed.isEmpty())
            	leafConjuncts.addAll(right.conjunctsUsed);
            PlanNode leftNode = left.joinPlan;
            PlanNode rightNode = right.joinPlan;

            // Combine the two JoinComponent objects.
            node = new NestedLoopsJoinNode(leftNode, rightNode,
                    JoinType.LEFT_OUTER, fromClause.getPreparedJoinExpr());
            if (fromClause.hasOuterJoinOnRight()) {
                // Right outer is not implemented in the NestedLoopsJoinNode, so
                // just do a left outer and call swap() to swap the left and right
                // children.
                NestedLoopsJoinNode joinNode = (NestedLoopsJoinNode) node;
                joinNode.swap();
            }
            break;
        default:
            break;
        }
        // The set of expressions applicable to fromClause
        HashSet<Expression> dstExprs = new HashSet<Expression>();
        
        // Prepare the node to compute its schema.
        node.prepare();
        schema = node.getSchema();
        PredicateUtils.findExprsUsingSchemas(conjunctsCopy, false, dstExprs, schema);
        
        Expression expr = PredicateUtils.makePredicate(dstExprs);
        if (expr != null) {
            node = PlanUtils.addPredicateToPlan(node, expr);
            leafConjuncts.addAll(dstExprs);

            node.prepare();
        }
        
        return node;
    }


    /**
     * This helper method builds up a full join-plan using a dynamic programming
     * approach.  The implementation maintains a collection of optimal
     * intermediate plans that join <em>n</em> of the leaf nodes, each with its
     * own associated cost, and then uses that collection to generate a new
     * collection of optimal intermediate plans that join <em>n+1</em> of the
     * leaf nodes.  This process completes when all leaf plans are joined
     * together; there will be <em>one</em> plan, and it will be the optimal
     * join plan (as far as our limited estimates can determine, anyway).
     *
     * @param leafComponents the collection of leaf join-components, generated
     *        by the {@link #generateLeafJoinComponents} method.
     *
     * @param conjuncts the collection of all conjuncts found in the query
     *
     * @return a single {@link JoinComponent} object that joins all leaf
     *         components together in an optimal way.
     */
    private JoinComponent generateOptimalJoin(
        ArrayList<JoinComponent> leafComponents, Set<Expression> conjuncts) {

        // This object maps a collection of leaf-plans (represented as a
        // hash-set) to the optimal join-plan for that collection of leaf plans.
        //
        // This collection starts out only containing the leaf plans themselves,
        // and on each iteration of the loop below, join-plans are grown by one
        // leaf.  For example:
        //   * In the first iteration, all plans joining 2 leaves are created.
        //   * In the second iteration, all plans joining 3 leaves are created.
        //   * etc.
        // At the end, the collection will contain ONE entry, which is the
        // optimal way to join all N leaves.  Go Go Gadget Dynamic Programming!

        HashMap<HashSet<PlanNode>, JoinComponent> joinPlans =
            new HashMap<HashSet<PlanNode>, JoinComponent>();

        // Initially populate joinPlans with just the N leaf plans.
        for (JoinComponent leaf : leafComponents) {
            joinPlans.put(leaf.leavesUsed, leaf);
        }

        for (JoinComponent leafComponent: leafComponents) {
            logger.debug(String.format("Leaf components received: %s", leafComponent.joinPlan));
        }


        while (joinPlans.size() > 1) {
            logger.debug("Current set of join-plans has " + joinPlans.size() +
                " plans in it.");

            // This is the set of "next plans" we will generate.  Plans only
            // get stored if they are the first plan that joins together the
            // specified leaves, or if they are better than the current plan.
            HashMap<HashSet<PlanNode>, JoinComponent> nextJoinPlans =
                new HashMap<HashSet<PlanNode>, JoinComponent>();

            for (HashSet<PlanNode> leafSet : joinPlans.keySet()) {
                JoinComponent jc = joinPlans.get(leafSet);
                for (JoinComponent leaf : leafComponents) {
                    if (leafSet.contains(leaf.joinPlan)) {
                        continue;
                    }
                    Expression expr = null;

                    HashSet<Expression> subplanConjuncts = new HashSet<Expression>(jc.conjunctsUsed);
                    subplanConjuncts.addAll(new HashSet<Expression>(leaf.conjunctsUsed));
                    HashSet<Expression> unusedConjuncts = new HashSet<Expression>(conjuncts);
                    unusedConjuncts.removeAll(subplanConjuncts);

                    // Conjuncts applicable to the join of the two subplans.
                    HashSet<Expression> exprs = new HashSet<Expression>();
                    PredicateUtils.findExprsUsingSchemas(unusedConjuncts, false, exprs, jc.joinPlan.getSchema(), leaf.joinPlan.getSchema());
                    expr = PredicateUtils.makePredicate(exprs);

                    PlanNode newPlan = new NestedLoopsJoinNode(jc.joinPlan, leaf.joinPlan, JoinType.INNER, expr);
                    newPlan.prepare();
                    float newCost = newPlan.getCost().cpuCost;

                    HashSet<PlanNode> unionLeafSet = new HashSet<PlanNode>(leafSet);
                    HashSet<Expression> newConjuncts = new HashSet<Expression>(subplanConjuncts);
                    newConjuncts.addAll(exprs);
                    unionLeafSet.add(leaf.joinPlan);
                    if (nextJoinPlans.containsKey(unionLeafSet)) {
                        JoinComponent bestJC = nextJoinPlans.get(unionLeafSet);
                        float bestCost = nextJoinPlans.get(unionLeafSet).joinPlan.getCost().cpuCost;
                        if (newCost < bestCost) {
                            logger.debug(String.format("Found better cost: newCost %f, bestCost %f", newCost, bestCost));
                            newConjuncts.addAll(exprs);
                            JoinComponent newJC = new JoinComponent(newPlan, newConjuncts);
                            newJC.leavesUsed = unionLeafSet;
                            nextJoinPlans.put(unionLeafSet, newJC);
                        }
                    } else {
                        JoinComponent newJC = new JoinComponent(newPlan, newConjuncts);
                        newJC.leavesUsed = unionLeafSet;
                        nextJoinPlans.put(unionLeafSet, newJC);
                    }
                }
            }

            // Now that we have generated all plans joining N leaves, time to
            // create all plans joining N + 1 leaves.
            joinPlans = nextJoinPlans;
        }

        // At this point, the set of join plans should only contain one plan,
        // and it should be the optimal plan.

        assert joinPlans.size() == 1 : "There can be only one optimal join plan!";
        return joinPlans.values().iterator().next();
    }


    /**
     * This helper function takes a collection of conjuncts that should comprise
     * a predicate, and creates a predicate for evaluating these conjuncts.  The
     * exact nature of the predicate depends on the conjuncts:
     * <ul>
     *   <li>If the collection contains only one conjunct, the method simply
     *       returns that one conjunct.</li>
     *   <li>If the collection contains two or more conjuncts, the method
     *       returns a {@link BooleanOperator} that performs an <tt>AND</tt> of
     *       all conjuncts.</li>
     *   <li>If the collection contains <em>no</em> conjuncts then the method
     *       returns <tt>null</tt>.
     * </ul>
     *
     * @param conjuncts the collection of conjuncts to combine into a predicate.
     *
     * @return a predicate for evaluating the conjuncts, or <tt>null</tt> if the
     *         input collection contained no conjuncts.
     */
    private Expression makePredicate(Collection<Expression> conjuncts) {
        Expression predicate = null;
        if (conjuncts.size() == 1) {
            predicate = conjuncts.iterator().next();
        }
        else if (conjuncts.size() > 1) {
            predicate = new BooleanOperator(
                BooleanOperator.Type.AND_EXPR, conjuncts);
        }
        return predicate;
    }


    /**
     * Constructs a simple select plan that reads directly from a table, with
     * an optional predicate for selecting rows.
     * <p>
     * While this method can be used for building up larger <tt>SELECT</tt>
     * queries, the returned plan is also suitable for use in <tt>UPDATE</tt>
     * and <tt>DELETE</tt> command evaluation.  In these cases, the plan must
     * only generate tuples of type {@link edu.caltech.nanodb.storage.PageTuple},
     * so that the command can modify or delete the actual tuple in the file's
     * page data.
     *
     * @param tableName The name of the table that is being selected from.
     *
     * @param predicate An optional selection predicate, or {@code null} if
     *        no filtering is desired.
     *
     * @return A new plan-node for evaluating the select operation.
     *
     * @throws IOException if an error occurs when loading necessary table
     *         information.
     */
    public SelectNode makeSimpleSelect(String tableName, Expression predicate,
        List<SelectClause> enclosingSelects) throws IOException {
        if (tableName == null)
            throw new IllegalArgumentException("tableName cannot be null");

        if (enclosingSelects != null) {
            // If there are enclosing selects, this subquery's predicate may
            // reference an outer query's value, but we don't detect that here.
            // Therefore we will probably fail with an unrecognized column
            // reference.
            logger.warn("Currently we are not clever enough to detect " +
                "correlated subqueries, so expect things are about to break...");
        }

        // Open the table.
        TableInfo tableInfo = storageManager.getTableManager().openTable(tableName);

        // Make a SelectNode to read rows from the table, with the specified
        // predicate.
        SelectNode selectNode = new FileScanNode(tableInfo, predicate);
        selectNode.prepare();
        return selectNode;
    }
}
