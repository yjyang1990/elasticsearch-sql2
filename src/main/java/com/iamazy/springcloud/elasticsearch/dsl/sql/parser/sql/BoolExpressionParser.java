package com.iamazy.springcloud.elasticsearch.dsl.sql.parser.sql;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.*;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.exact.InListAtomQueryParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.method.MethodInvocation;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.method.fulltext.FullTextAtomicQueryParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.enums.SqlBoolOperator;
import com.iamazy.springcloud.elasticsearch.dsl.sql.enums.SqlConditionType;
import com.iamazy.springcloud.elasticsearch.dsl.sql.exception.ElasticSql2DslException;
import com.iamazy.springcloud.elasticsearch.dsl.sql.listener.ParseActionListener;
import com.iamazy.springcloud.elasticsearch.dsl.sql.listener.ParseActionListenerAdapter;
import com.iamazy.springcloud.elasticsearch.dsl.sql.model.AtomicQuery;
import com.iamazy.springcloud.elasticsearch.dsl.sql.model.SqlArgs;
import com.iamazy.springcloud.elasticsearch.dsl.sql.model.SqlCondition;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.exact.BetweenAndAtomicQueryParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.exact.BinaryAtomicQueryParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.method.join.JoinAtomicQueryParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.method.script.ScriptAtomicQueryParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.query.method.term.TermLevelAtomicQueryParser;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;

import java.util.List;

public class BoolExpressionParser {

    private final TermLevelAtomicQueryParser termLevelAtomicQueryParser;
    private final ScriptAtomicQueryParser scriptAtomicQueryParser;
    private final FullTextAtomicQueryParser fullTextAtomQueryParser;
    private final BinaryAtomicQueryParser binaryQueryParser;
    private final InListAtomQueryParser inListQueryParser;
    private final BetweenAndAtomicQueryParser betweenAndQueryParser;

    private final JoinAtomicQueryParser joinAtomQueryParser;

    public BoolExpressionParser() {
        this(new ParseActionListenerAdapter());
    }

    public BoolExpressionParser(ParseActionListener parseActionListener) {
        termLevelAtomicQueryParser = new TermLevelAtomicQueryParser(parseActionListener);
        fullTextAtomQueryParser = new FullTextAtomicQueryParser(parseActionListener);
        binaryQueryParser = new BinaryAtomicQueryParser(parseActionListener);
        inListQueryParser = new InListAtomQueryParser(parseActionListener);
        betweenAndQueryParser = new BetweenAndAtomicQueryParser(parseActionListener);

        scriptAtomicQueryParser = new ScriptAtomicQueryParser();
        joinAtomQueryParser = new JoinAtomicQueryParser();
    }


    public BoolQueryBuilder parseBoolQueryExpr(SQLExpr conditionExpr, String queryAs, SqlArgs sqlArgs) {
        SqlCondition sqlCondition = recursiveParseBoolQueryExpr(conditionExpr, queryAs, sqlArgs);
        SqlBoolOperator operator = sqlCondition.getOperator();

        if (SqlConditionType.Atom == sqlCondition.getConditionType()) {
            operator = SqlBoolOperator.AND;
        }
        return mergeAtomQuery(sqlCondition.getQueryList(), operator);
    }

    private SqlCondition recursiveParseBoolQueryExpr(SQLExpr conditionExpr, String queryAs, SqlArgs sqlArgs) {
        if (conditionExpr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binOpExpr = (SQLBinaryOpExpr) conditionExpr;
            SQLBinaryOperator binOperator = binOpExpr.getOperator();

            if (SQLBinaryOperator.BooleanAnd == binOperator || SQLBinaryOperator.BooleanOr == binOperator) {
                SqlBoolOperator operator = SQLBinaryOperator.BooleanAnd == binOperator ? SqlBoolOperator.AND : SqlBoolOperator.OR;

                SqlCondition leftCondition = recursiveParseBoolQueryExpr(binOpExpr.getLeft(), queryAs, sqlArgs);
                SqlCondition rightCondition = recursiveParseBoolQueryExpr(binOpExpr.getRight(), queryAs, sqlArgs);

                List<AtomicQuery> mergedQueryList = Lists.newArrayList();
                combineQueryBuilder(mergedQueryList, leftCondition, operator);
                combineQueryBuilder(mergedQueryList, rightCondition, operator);

                return new SqlCondition(mergedQueryList, operator);
            }
        }
        else if (conditionExpr instanceof SQLNotExpr) {
            SqlCondition innerSQLCondition = recursiveParseBoolQueryExpr(((SQLNotExpr) conditionExpr).getExpr(), queryAs, sqlArgs);

            SqlBoolOperator operator = innerSQLCondition.getOperator();
            if (SqlConditionType.Atom == innerSQLCondition.getConditionType()) {
                operator = SqlBoolOperator.AND;
            }

            BoolQueryBuilder boolQuery = mergeAtomQuery(innerSQLCondition.getQueryList(), operator);
            boolQuery = QueryBuilders.boolQuery().mustNot(boolQuery);

            return new SqlCondition(new AtomicQuery(boolQuery), SqlConditionType.Atom);
        }

        return new SqlCondition(parseAtomQueryCondition(conditionExpr, queryAs, sqlArgs), SqlConditionType.Atom);
    }

    private AtomicQuery parseAtomQueryCondition(SQLExpr sqlConditionExpr, String queryAs, SqlArgs sqlArgs) {
        if (sqlConditionExpr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr methodQueryExpr = (SQLMethodInvokeExpr) sqlConditionExpr;

            MethodInvocation methodInvocation = new MethodInvocation(methodQueryExpr, queryAs, sqlArgs);

            if (scriptAtomicQueryParser.isMatchMethodInvocation(methodInvocation)) {
                return scriptAtomicQueryParser.parseAtomMethodQuery(methodInvocation);
            }

            if (fullTextAtomQueryParser.isFulltextAtomQuery(methodInvocation)) {
                return fullTextAtomQueryParser.parseFullTextAtomQuery(methodQueryExpr, queryAs, sqlArgs);
            }

            if (termLevelAtomicQueryParser.isTermLevelAtomQuery(methodInvocation)) {
                return termLevelAtomicQueryParser.parseTermLevelAtomQuery(methodQueryExpr, queryAs, sqlArgs);
            }

            if (joinAtomQueryParser.isJoinAtomQuery(methodInvocation)) {
                return joinAtomQueryParser.parseJoinAtomQuery(methodQueryExpr, queryAs, sqlArgs);
            }
        }
        else if (sqlConditionExpr instanceof SQLBinaryOpExpr) {
            return binaryQueryParser.parseBinaryQuery((SQLBinaryOpExpr) sqlConditionExpr, queryAs, sqlArgs);
        }
        else if (sqlConditionExpr instanceof SQLInListExpr) {
            return inListQueryParser.parseInListQuery((SQLInListExpr) sqlConditionExpr, queryAs, sqlArgs);
        }
        else if (sqlConditionExpr instanceof SQLBetweenExpr) {
            return betweenAndQueryParser.parseBetweenAndQuery((SQLBetweenExpr) sqlConditionExpr, queryAs, sqlArgs);
        }

        throw new ElasticSql2DslException(String.format("[syntax error] Can not support query condition type[%s]", sqlConditionExpr.toString()));
    }

    private void combineQueryBuilder(List<AtomicQuery> combiner, SqlCondition sqlCondition, SqlBoolOperator binOperator) {
        if (SqlConditionType.Atom == sqlCondition.getConditionType() || sqlCondition.getOperator() == binOperator) {
            combiner.addAll(sqlCondition.getQueryList());
        }
        else {
            BoolQueryBuilder boolQuery = mergeAtomQuery(sqlCondition.getQueryList(), sqlCondition.getOperator());
            combiner.add(new AtomicQuery(boolQuery));
        }
    }

    private BoolQueryBuilder mergeAtomQuery(List<AtomicQuery> atomQueryList, SqlBoolOperator operator) {
        BoolQueryBuilder subBoolQuery = QueryBuilders.boolQuery();
        ListMultimap<String, QueryBuilder> listMultiMap = ArrayListMultimap.create();

        for (AtomicQuery atomQuery : atomQueryList) {
            if (Boolean.FALSE == atomQuery.isNestedQuery()) {
                if (operator == SqlBoolOperator.AND) {
                    subBoolQuery.must(atomQuery.getQueryBuilder());
                }
                if (operator == SqlBoolOperator.OR) {
                    subBoolQuery.should(atomQuery.getQueryBuilder());
                }
            }
            else {
                String nestedDocPrefix = atomQuery.getNestedQueryPath();
                listMultiMap.put(nestedDocPrefix, atomQuery.getQueryBuilder());
            }
        }

        for (String nestedDocPrefix : listMultiMap.keySet()) {
            List<QueryBuilder> nestedQueryList = listMultiMap.get(nestedDocPrefix);

            if (nestedQueryList.size() == 1) {
                if (operator == SqlBoolOperator.AND) {
                    subBoolQuery.must(QueryBuilders.nestedQuery(nestedDocPrefix, nestedQueryList.get(0), ScoreMode.None));
                }
                if (operator == SqlBoolOperator.OR) {
                    subBoolQuery.should(QueryBuilders.nestedQuery(nestedDocPrefix, nestedQueryList.get(0), ScoreMode.None));
                }
                continue;
            }

            BoolQueryBuilder boolNestedQuery = QueryBuilders.boolQuery();
            for (QueryBuilder nestedQueryItem : nestedQueryList) {
                if (operator == SqlBoolOperator.AND) {
                    boolNestedQuery.must(nestedQueryItem);
                }
                if (operator == SqlBoolOperator.OR) {
                    boolNestedQuery.should(nestedQueryItem);
                }
            }

            if (operator == SqlBoolOperator.AND) {
                subBoolQuery.must(QueryBuilders.nestedQuery(nestedDocPrefix, boolNestedQuery, ScoreMode.None));
            }
            if (operator == SqlBoolOperator.OR) {
                subBoolQuery.should(QueryBuilders.nestedQuery(nestedDocPrefix, boolNestedQuery, ScoreMode.None));
            }

        }
        return subBoolQuery;
    }


}