package edu.caltech.test.nanodb.sql;


import edu.caltech.nanodb.expressions.TupleLiteral;
import edu.caltech.nanodb.server.CommandResult;
import org.testng.annotations.Test;


/**
 * This class tests some simple inner and outer joins.
 */
@Test
public class TestSimpleJoins extends SqlTestCase {

    public TestSimpleJoins() {
        super("setup_testSimpleJoins");
    }


    /**
     * ************************************************************
     * ********************* INNER JOIN TESTS *********************
     * ************************************************************
     */

    /**
     * This test performs a simple inner join between two non-empty tables. Not every column
     * should join.
     */
    public void testInnerSimple() throws Throwable {
        TupleLiteral[] expected = {
            new TupleLiteral(10, 1, 1, 100),
            new TupleLiteral(20, 2, 2, 200),
            new TupleLiteral(30, 3, 3, 300),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 INNER JOIN test_joins_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This test performs an inner join between a empty table (right) and a non-empty table.
     * @throws Throwable
     */
    public void testInnerEmptyRight() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 INNER JOIN test_joins_empty AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This test performs an inner join between a empty table (left) and a non-empty table.
     * @throws Throwable
     */
    public void testInnerEmptyLeft() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_empty AS t1 INNER JOIN test_joins_1 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This test performs an inner join between two empty tables;
     * @throws Throwable
     */
    public void testInnerEmptyBoth() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_empty AS t1 INNER JOIN test_joins_empty_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This tests performs an inner join between two nonempty tables where multiple rows of
     * the right table would join.
     * @throws Throwable
     */
    public void testInnerDupRight() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(20, 2, 2, 222),
                new TupleLiteral(30, 3, 3, 300),
                new TupleLiteral(40, 4, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_dup_1 AS t1 INNER JOIN test_joins_dup_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This tests performs an inner join between two nonempty tables where multiple rows of
     * the left table would join.
     * @throws Throwable
     */
    public void testInnerDupLeft() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(22, 2, 2, 200),
                new TupleLiteral(30, 3, 3, 300),
                new TupleLiteral(40, 4, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_dup_3 AS t1 INNER JOIN test_joins_dup_4 AS t2 ON t1.b = t2.b", true);
        // TODO: Debug only, remove eventually
        System.out.println(">>>>>>>>> dbginf >>>>>>");
        for (TupleLiteral tup : expected) {
            System.out.println(" * " + tup.toString());
        }
        for (TupleLiteral tup : result.getTuples()) {
            System.out.println(" * " + tup.toString());
        }

        assert checkUnorderedResults(expected, result);
    }

    /**
     * ************************************************************
     * ********************* LEFT OUTER JOIN TESTS ****************
     * ************************************************************
     */

    /**
     * This test performs a simple left outer join between two non-empty tables. Not every column
     * should join.
     */
    public void testLOJSimple() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(0, 0, null, null),
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(30, 3, 3, 300),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 LEFT OUTER JOIN test_joins_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This test performs a left outer join between a empty table (right) and a non-empty table.
     * @throws Throwable
     */
    public void testLOJEmptyRight() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(0, 0, null, null),
                new TupleLiteral(10, 1, null, null),
                new TupleLiteral(20, 2, null, null),
                new TupleLiteral(30, 3, null, null),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 LEFT OUTER JOIN test_joins_empty AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This test performs a left outer join between a empty table (left) and a non-empty table.
     * @throws Throwable
     */
    public void testLOJEmptyLeft() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_empty AS t1 LEFT OUTER JOIN test_joins_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This test performs a left outer join between two empty tables;
     * @throws Throwable
     */
    public void testLOJEmptyBoth() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_empty AS t1 LEFT OUTER JOIN test_joins_empty_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This tests performs a left outer join between two nonempty tables where multiple rows of
     * the right table would join.
     * @throws Throwable
     */
    public void testLOJDupRight() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(0, 0, null, null),
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(20, 2, 2, 222),
                new TupleLiteral(30, 3, 3, 300),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 LEFT OUTER JOIN test_joins_dup_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This tests performs a left outer join between two nonempty tables where multiple rows of
     * the left table would join.
     * @throws Throwable
     */
    public void testLOJDupLeft() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(22, 2, 2, 200),
                new TupleLiteral(30, 3, 3, 300),
                new TupleLiteral(40, 4, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_dup_3 AS t1 LEFT OUTER JOIN test_joins_dup_4 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * ************************************************************
     * ********************* RIGHT OUTER JOIN TESTS ***************
     * ************************************************************
     */


    /**
     * This test performs a simple right outer join between two non-empty tables. Not every column
     * should join.
     */
    public void testROJSimple() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(30, 3, 3, 300),
                new TupleLiteral(null, null, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 RIGHT OUTER JOIN test_joins_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This test performs a right outer join between a empty table (right) and a non-empty table.
     * @throws Throwable
     */
    public void testROJEmptyRight() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 RIGHT OUTER JOIN test_joins_empty AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This test performs a right outer join between a empty table (left) and a non-empty table.
     * @throws Throwable
     */
    public void testROJEmptyLeft() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(null, null, 1, 100),
                new TupleLiteral(null, null, 2, 200),
                new TupleLiteral(null, null, 3, 300),
                new TupleLiteral(null, null, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_empty AS t1 RIGHT OUTER JOIN test_joins_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This test performs a right outer join between two empty tables;
     * @throws Throwable
     */
    public void testROJEmptyBoth() throws Throwable {
        TupleLiteral[] expected = {
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_empty AS t1 RIGHT OUTER JOIN test_joins_empty_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This tests performs a right outer join between two nonempty tables where multiple rows of
     * the right table would join.
     * @throws Throwable
     */
    public void testROJDupRight() throws Throwable {
        TupleLiteral[] expected = {
            new TupleLiteral(10, 1, 1, 100),
            new TupleLiteral(20, 2, 2, 200),
            new TupleLiteral(20, 2, 2, 222),
            new TupleLiteral(30, 3, 3, 300),
            new TupleLiteral(null, null, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_1 AS t1 RIGHT OUTER JOIN test_joins_dup_2 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    /**
     * This tests performs a right outer join between two nonempty tables where multiple rows of
     * the left table would join.
     * @throws Throwable
     */
    public void testROJDupLeft() throws Throwable {
        TupleLiteral[] expected = {
                new TupleLiteral(10, 1, 1, 100),
                new TupleLiteral(20, 2, 2, 200),
                new TupleLiteral(22, 2, 2, 200),
                new TupleLiteral(30, 3, 3, 300),
                new TupleLiteral(40, 4, 4, 400),
        };

        CommandResult result;

        result = server.doCommand("SELECT * FROM test_joins_dup_3 AS t1 RIGHT OUTER JOIN test_joins_dup_4 AS t2 ON t1.b = t2.b", true);
        assert checkUnorderedResults(expected, result);
    }

    // TODO: Natural join tests (in different class)
    // TODO: "USING" tests (in different class)


}
