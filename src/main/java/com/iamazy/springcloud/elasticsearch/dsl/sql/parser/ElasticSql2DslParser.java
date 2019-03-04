package com.iamazy.springcloud.elasticsearch.dsl.sql.parser;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.parser.Token;
import com.google.common.collect.ImmutableList;
import com.iamazy.springcloud.elasticsearch.dsl.sql.model.ElasticSqlParseResult;
import com.iamazy.springcloud.elasticsearch.dsl.sql.druid.ElasticSqlExprParser;
import com.iamazy.springcloud.elasticsearch.dsl.sql.druid.ElasticSqlSelectQueryBlock;
import com.iamazy.springcloud.elasticsearch.dsl.sql.exception.ElasticSql2DslException;
import com.iamazy.springcloud.elasticsearch.dsl.sql.listener.ParseActionListener;
import com.iamazy.springcloud.elasticsearch.dsl.sql.listener.ParseActionListenerAdapter;
import com.iamazy.springcloud.elasticsearch.dsl.sql.model.ElasticDslContext;
import com.iamazy.springcloud.elasticsearch.dsl.sql.model.SqlArgs;
import com.iamazy.springcloud.elasticsearch.dsl.sql.parser.sql.*;


import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;

/**
 *
 * @author iamazy
 */
public class ElasticSql2DslParser {

    public ElasticSqlParseResult parse(String sql) throws ElasticSql2DslException {
        return parse(sql, null, new ParseActionListenerAdapter());
    }

    public ElasticSqlParseResult parse(String sql, ParseActionListener parseActionListener) throws ElasticSql2DslException {
        return parse(sql, null, parseActionListener);
    }

    public ElasticSqlParseResult parse(String sql, Object[] sqlArgs) throws ElasticSql2DslException {
        return parse(sql, sqlArgs, new ParseActionListenerAdapter());
    }

    public ElasticSqlParseResult parse(String sql, Object[] sqlArgs, ParseActionListener parseActionListener) throws ElasticSql2DslException {
        SQLQueryExpr queryExpr;
        try {
            SQLStatementParser sqlStatementParser=new SQLStatementParser(sql);
            Token token=sqlStatementParser.getLexer().token();
            switch (token){
                case DELETE:{
                    SQLDeleteStatement sqlDeleteStatement = sqlStatementParser.parseDeleteStatement();
                    SQLLimit sqlLimit = sqlStatementParser.getExprParser().parseLimit();
                    SqlArgs sqlParamValues = (sqlArgs != null && sqlArgs.length > 0) ? new SqlArgs(sqlArgs) : null;
                    ElasticDslContext elasticDslContext = new ElasticDslContext(sqlDeleteStatement, sqlParamValues);
                    for (QueryParser sqlParser : buildSqlDeleteParserChain(parseActionListener)) {
                        sqlParser.parse(elasticDslContext);
                    }
                    //此处设置的是DeleteByQueryRequest的Size，将DeleteByQueryRequest中的SearchRequest的DSL打印出来的size是1000，不是这个值，不要搞混淆
                    elasticDslContext.getParseResult().setSize(((SQLIntegerExpr)sqlLimit.getRowCount()).getNumber().intValue());
                    return elasticDslContext.getParseResult();
                }
                case SELECT:
                default:{
                    ElasticSqlExprParser elasticSqlExprParser=new ElasticSqlExprParser(sql);
                    SQLExpr sqlQueryExpr = elasticSqlExprParser.expr();
                    check(elasticSqlExprParser, sqlQueryExpr, sqlArgs);
                    queryExpr = (SQLQueryExpr) sqlQueryExpr;
                    SqlArgs sqlParamValues = (sqlArgs != null && sqlArgs.length > 0) ? new SqlArgs(sqlArgs) : null;
                    ElasticDslContext elasticDslContext = new ElasticDslContext(queryExpr, sqlParamValues);

                    if (queryExpr.getSubQuery().getQuery() instanceof ElasticSqlSelectQueryBlock) {
                        for (QueryParser sqlParser : buildSqlSelectParserChain(parseActionListener)) {
                            sqlParser.parse(elasticDslContext);
                        }
                    }
                    else {
                        throw new ElasticSql2DslException("[syntax error] Sql only support Select,Delete Sql");
                    }
                    return elasticDslContext.getParseResult();
                }
            }

        }
        catch (ParserException ex) {
            throw new ElasticSql2DslException(ex);
        }

    }

    private void check(ElasticSqlExprParser sqlExprParser, SQLExpr sqlQueryExpr, Object[] sqlArgs) {
        if (sqlExprParser.getLexer().token() != Token.EOF) {
            throw new ElasticSql2DslException("[syntax error] Sql last token is not EOF");
        }

        if (!(sqlQueryExpr instanceof SQLQueryExpr)) {
            throw new ElasticSql2DslException("[syntax error] Sql is not select druid");
        }

        if (sqlArgs != null && sqlArgs.length > 0) {
            for (Object arg : sqlArgs) {
                if (arg instanceof Array || arg instanceof Collection) {
                    throw new ElasticSql2DslException("[syntax error] Sql arg cannot support collection type");
                }
            }
        }
    }

    private List<QueryParser> buildSqlSelectParserChain(ParseActionListener parseActionListener) {
        //SQL解析器的顺序不能改变
        return ImmutableList.of(
                //解析SQL指定的索引和文档类型
                new QueryFromParser(parseActionListener),
                //解析SQL查询指定的match条件
                new QueryMatchConditionParser(parseActionListener),
                //解析SQL查询指定的where条件
                new QueryWhereConditionParser(parseActionListener),
                //解析SQL排序条件
                new QueryOrderConditionParser(parseActionListener),
                //解析路由参数
                new QueryRoutingValParser(parseActionListener),
                //解析分组统计
                new QueryGroupByParser(),
                //解析SQL查询指定的字段
                new QuerySelectFieldListParser(parseActionListener),
                //解析SQL的分页条数
                new QueryLimitSizeParser(parseActionListener)
        );
    }

    private List<QueryParser> buildSqlDeleteParserChain(ParseActionListener parseActionListener) {
        //SQL解析器的顺序不能改变
        return ImmutableList.of(
                //解析SQL指定的索引和文档类型
                new QueryFromParser(parseActionListener),
                //解析SQL查询指定的where条件
                new QueryWhereConditionParser(parseActionListener),
                //解析路由参数
                new QueryRoutingValParser(parseActionListener)
        );
    }
}