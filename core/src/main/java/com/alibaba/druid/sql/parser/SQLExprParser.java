/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlCharExpr;
import com.alibaba.druid.sql.dialect.oracle.ast.expr.OracleArgumentExpr;
import com.alibaba.druid.sql.parser.Lexer.SavePoint;
import com.alibaba.druid.util.FnvHash;
import com.alibaba.druid.util.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.alibaba.druid.sql.parser.DialectFeature.ParserFeature.*;
import static com.alibaba.druid.sql.parser.Token.*;

public class SQLExprParser extends SQLParser {
    public static final String[] AGGREGATE_FUNCTIONS;

    public static final long[] AGGREGATE_FUNCTIONS_CODES;
    private static final List<String> NESTED_DATA_TYPE;

    static {
        String[] strings = {"AVG", "COUNT", "MAX", "MIN", "STDDEV", "SUM"};
        AGGREGATE_FUNCTIONS_CODES = FnvHash.fnv1a_64_lower(strings, true);
        AGGREGATE_FUNCTIONS = new String[AGGREGATE_FUNCTIONS_CODES.length];
        for (String str : strings) {
            long hash = FnvHash.fnv1a_64_lower(str);
            int index = Arrays.binarySearch(AGGREGATE_FUNCTIONS_CODES, hash);
            AGGREGATE_FUNCTIONS[index] = str;
        }
        NESTED_DATA_TYPE = Collections.singletonList("array");
    }

    protected String[] aggregateFunctions = AGGREGATE_FUNCTIONS;
    protected long[] aggregateFunctionHashCodes = AGGREGATE_FUNCTIONS_CODES;
    protected List<String> nestedDataType = NESTED_DATA_TYPE;

    protected boolean allowIdentifierMethod = true;

    public SQLExprParser(String sql) {
        super(sql);
    }

    public SQLExprParser(String sql, DbType dbType, SQLParserFeature... features) {
        super(sql, dbType, features);
    }

    public SQLExprParser(Lexer lexer) {
        super(lexer);
    }

    public SQLExprParser(Lexer lexer, DbType dbType) {
        super(lexer, dbType);
    }

    public void setAllowIdentifierMethod(boolean allowIdentifierMethod) {
        this.allowIdentifierMethod = allowIdentifierMethod;
    }

    public SQLExpr expr() {
        if (lexer.token == Token.STAR) {
            lexer.nextToken();

            SQLAllColumnExpr expr = new SQLAllColumnExpr();

            if (lexer.token == Token.DOT) {
                lexer.nextToken();
                accept(Token.STAR);
                return new SQLPropertyExpr(expr, "*");
            }

            if (lexer.token == Token.EXCEPT) {
                lexer.nextToken();
                accept(Token.LPAREN);
                List<SQLExpr> except = new ArrayList<>();
                this.exprList(except, expr);
                expr.setExcept(except);
                accept(Token.RPAREN);
            }

            return expr;
        }

        boolean parenthesized = (lexer.token == Token.LPAREN);
        SQLExpr expr = primary();
        if (parenthesized && expr instanceof SQLBinaryOpExpr) {
            if (((SQLBinaryOpExpr) expr).isParenthesized()) {
                parenthesized = false;
            }
        }
        if (parenthesized && expr instanceof SQLCaseExpr) {
            parenthesized = false;
            ((SQLCaseExpr) expr).setParenthesized(true);
        }
        if (parenthesized && expr instanceof SQLUnaryExpr) {
            if (((SQLUnaryExpr) expr).isParenthesized()) {
                parenthesized = false;
            }
        }
        if (parenthesized && expr instanceof SQLQueryExpr) {
            parenthesized = false;
            ((SQLQueryExpr) expr).setParenthesized(true);
        }
        if (parenthesized && expr instanceof SQLIdentifierExpr) {
            parenthesized = false;
            ((SQLIdentifierExpr) expr).setParenthesized(true);
        }
        Lexer.SavePoint mark = lexer.mark();
        Token token = lexer.token;
        if (token == Token.COMMA) {
            return expr;
        } else if (token == Token.EQ || token == Token.EQEQ) {
            expr = relationalRest(expr);
            expr = andRest(expr);
            expr = xorRest(expr);
            expr = orRest(expr);
            return expr;
        }
        if (token == Token.IN) {
            lexer.nextToken();
            if (lexer.token == Token.PARTITION) {
                lexer.reset(mark);
                return expr;
            } else {
                lexer.reset(mark);
                return exprRest(expr);
            }
        } else {
            SQLExpr sqlExpr = exprRest(expr);
            if (token == SUBGT) {
                parenthesized = false;
            }
            if (parenthesized && sqlExpr instanceof SQLBinaryOpExpr) {
                ((SQLBinaryOpExpr) sqlExpr).setParenthesized(true);
            }
            if (parenthesized && sqlExpr instanceof SQLUnaryExpr) {
                ((SQLUnaryExpr) sqlExpr).setParenthesized(true);
            }
            if (parenthesized && sqlExpr instanceof MySqlCharExpr) {
                ((MySqlCharExpr) sqlExpr).setParenthesized(true);
            }
            return sqlExpr;
        }
    }

    public SQLExpr exprRest(SQLExpr expr) {
        expr = bitXorRest(expr);
        expr = multiplicativeRest(expr);
        expr = additiveRest(expr);
        expr = shiftRest(expr);
        expr = bitAndRest(expr);
        expr = bitOrRest(expr);
        expr = inRest(expr);
        expr = relationalRest(expr);
//        expr = equalityRest(expr);
        expr = andRest(expr);
        expr = xorRest(expr);
        expr = orRest(expr);

        return expr;
    }

    public final SQLExpr bitXor() {
        SQLExpr expr = primary();
        return bitXorRest(expr);
    }

    protected SQLExpr bitXorRestSUBGT() {
        return primary();
    }
    public SQLExpr bitXorRest(SQLExpr expr) {
        Token token = lexer.token;
        switch (token) {
            case CARET: {
                lexer.nextToken();
                SQLBinaryOperator op;
                if (lexer.token == Token.EQ) {
                    lexer.nextToken();
                    op = SQLBinaryOperator.BitwiseXorEQ;
                } else {
                    op = SQLBinaryOperator.BitwiseXor;
                }
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, op, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case SUBGT: {
                lexer.nextToken();
                SQLExpr rightExp = bitXorRestSUBGT();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.SubGt, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case LT_SUB_GT: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.PG_ST_DISTANCE, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case SUBGTGT: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.SubGtGt, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case POUNDGT: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.PoundGt, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case POUNDGTGT: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.PoundGtGt, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case QUESQUES: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.QuesQues, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case QUESBAR: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.QuesBar, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case QUESQUESBAR: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.QuesQuesBar, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }
            case QUESAMP: {
                lexer.nextToken();
                SQLExpr rightExp = primary();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.QuesAmp, rightExp, dbType);
                expr = bitXorRest(expr);
                break;
            }

            default:
                break;
        }

        return expr;
    }

    public final SQLExpr multiplicative() {
        SQLExpr expr = bitXor();
        return multiplicativeRest(expr);
    }

    public SQLExpr multiplicativeRest(SQLExpr expr) {
        final Token token = lexer.token;
        if (token == Token.STAR) {
            lexer.nextToken();
            SQLExpr rightExp = bitXor();
            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Multiply, rightExp, getDbType());
            expr = multiplicativeRest(expr);
        } else if (token == Token.SLASH) {
            lexer.nextToken();
            SQLExpr rightExp = bitXor();
            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Divide, rightExp, getDbType());
            expr = multiplicativeRest(expr);
        } else if (token == Token.PERCENT) {
            lexer.nextToken();
            SQLExpr rightExp = bitXor();
            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Modulus, rightExp, getDbType());
            expr = multiplicativeRest(expr);
        } else if (token == Token.DIV) {
            lexer.nextToken();
            SQLExpr rightExp = bitXor();
            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.DIV, rightExp, getDbType());
            expr = multiplicativeRest(expr);
        } else if (lexer.identifierEquals(FnvHash.Constants.MOD) || lexer.token == Token.MOD) {
            Lexer.SavePoint savePoint = lexer.mark();
            lexer.nextToken();

            if (lexer.token == Token.COMMA
                    || lexer.token == Token.EOF
                    || lexer.token == Token.ON
                    || lexer.token == Token.WHERE
                    || lexer.token == Token.RPAREN) {
                lexer.reset(savePoint);
                return expr;
            }

            SQLExpr rightExp = bitXor();

            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Modulus, rightExp, dbType);

            expr = multiplicativeRest(expr);
        } else if (token == Token.LITERAL_INT && lexer.isNegativeIntegerValue()) {
            Number number = lexer.integerValue();
            if (number instanceof Integer) {
                number = -number.intValue();
            } else if (number instanceof Long) {
                number = -number.longValue();
            } else if (number instanceof BigInteger) {
                number = ((BigInteger) number).abs();
            } else {
                throw new ParserException("not support value : " + number + ", " + lexer.info());
            }
            SQLIntegerExpr rightExp = new SQLIntegerExpr(number);
            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Subtract, rightExp, dbType);

            lexer.nextToken();
            expr = multiplicativeRest(expr);

        }
        return expr;
    }

    public SQLIntegerExpr integerExpr() {
        SQLIntegerExpr intExpr = null;
        if (lexer.token() == Token.SUB) {
            lexer.nextToken();
            intExpr = new SQLIntegerExpr(lexer.integerValue().longValue() * -1);
        } else {
            intExpr = new SQLIntegerExpr(lexer.integerValue());
        }
        accept(Token.LITERAL_INT);
        return intExpr;
    }

    public SQLCharExpr charExpr() {
        SQLCharExpr charExpr = new SQLCharExpr(lexer.stringVal());
        accept(Token.LITERAL_CHARS);
        return charExpr;
    }

    public int parseIntValue() {
        if (lexer.token == Token.LITERAL_INT) {
            Number number = this.lexer.integerValue();
            int intVal = ((Integer) number).intValue();
            lexer.nextToken();
            return intVal;
        } else {
            throw new ParserException("not int. " + lexer.info());
        }
    }

    public void parseAssignItems(List<? super SQLAssignItem> items, SQLObject parent, boolean variant) {
        for (; ; ) {
            SQLAssignItem item = parseAssignItem(variant, parent);
            item.setParent(parent);
            items.add(item);

            if (lexer.token == Token.COMMA) {
                lexer.nextToken();
                continue;
            } else {
                break;
            }
        }
    }

    protected SQLExpr primaryIdentifierRest(long hash_lower, String ident) {
        SQLExpr sqlExpr = null;
        if (hash_lower == FnvHash.Constants.VARCHAR && lexer.token == Token.LITERAL_CHARS) {
            sqlExpr = new SQLCharExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.CHAR && lexer.token == Token.LITERAL_CHARS) {
            sqlExpr = new SQLCharExpr(lexer.stringVal());
            lexer.nextToken();
        }
        return sqlExpr;
    }

    protected SQLPropertyExpr topPropertyExpr(String ident) {
        String substring = ident.substring(1, ident.length() - 1);
        String[] items = substring.split("\\.");
        SQLPropertyExpr expr = new SQLPropertyExpr(items[0], items[1]);
        for (int i = 2; i < items.length; i++) {
            expr = new SQLPropertyExpr(expr, items[i]);
        }
        return expr;
    }

    protected SQLExpr primaryLiteralCharsRest(SQLExpr sqlExpr) {
        lexer.nextToken();
        return sqlExpr;
    }

    protected SQLExpr primaryLiteralNCharsRest(SQLExpr sqlExpr) {
        return sqlExpr;
    }

    protected SQLExpr primaryDefaultRest() {
        return new SQLDefaultExpr();
    }

    protected SQLExpr primarySubLiteralAliasRest() {
        return new SQLIdentifierExpr(lexer.stringVal());
    }

    protected void primaryQues() {
        lexer.nextToken();
    }

    protected SQLExpr primaryCommon(SQLExpr sqlExpr) {
        throw new ParserException("ERROR. " + lexer.info());
    }

    protected SQLExpr primaryAs(SQLExpr sqlExpr) {
        return sqlExpr;
    }

    protected SQLExpr primaryDistinct(SQLExpr sqlExpr) {
        throw new ParserException("ERROR. " + lexer.info());
    }

    protected SQLExpr primaryIn(SQLExpr sqlExpr) {
        throw new ParserException("ERROR. " + lexer.info());
    }

    protected SQLExpr primaryOn(SQLExpr sqlExpr) {
        throw new ParserException("ERROR. " + lexer.info());
    }

    protected SQLExpr primaryColonColon(SQLExpr sqlExpr) {
        throw new ParserException("ERROR. " + lexer.info());
    }

    public SQLExpr primary() {
        List<String> beforeComments = null;
        if (lexer.isKeepComments() && lexer.hasComment()) {
            beforeComments = lexer.readAndResetComments();
        }

        SQLExpr sqlExpr = null;

        final Token tok = lexer.token;

        switch (tok) {
            case LPAREN:
                lexer.nextToken();
                if (lexer.token == Token.RPAREN) {
                    lexer.nextToken();
                    sqlExpr = new SQLMethodInvokeExpr();
                    break;
                }
                sqlExpr = expr();

                if (lexer.token == Token.COMMA) {
                    SQLListExpr listExpr = new SQLListExpr();
                    listExpr.addItem(sqlExpr);
                    do {
                        lexer.nextToken();
                        listExpr.addItem(expr());
                    } while (lexer.token == Token.COMMA);

                    sqlExpr = listExpr;
                }
                if (sqlExpr instanceof SQLExprImpl) {
                    ((SQLExprImpl) sqlExpr).setParenthesized(true);
                }

                if ((lexer.token == Token.UNION || lexer.token == Token.MINUS || lexer.token == Token.EXCEPT)
                        && sqlExpr instanceof SQLQueryExpr) {
                    SQLQueryExpr queryExpr = (SQLQueryExpr) sqlExpr;

                    SQLSelectQuery query = this.createSelectParser().queryRest(queryExpr.getSubQuery().getQuery(), true);
                    queryExpr.getSubQuery().setQuery(query);
                }

                accept(Token.RPAREN);
                break;
            case INSERT:
                lexer.nextToken();
                if (lexer.token != Token.LPAREN) {
                    throw new ParserException("syntax error. " + lexer.info());
                }
                sqlExpr = new SQLIdentifierExpr("INSERT");
                break;
            case IDENTIFIER:
                String ident = lexer.stringVal();
                long hash_lower = lexer.hashLCase();

                int sourceLine = -1, sourceColumn = -1;
                if (lexer.keepSourceLocation) {
                    lexer.computeRowAndColumn();
                    sourceLine = lexer.posLine;
                    sourceColumn = lexer.posColumn;
                }

                lexer.nextToken();
                if (lexer.identifierEquals("COLLATE")) {
                    acceptIdentifier("COLLATE");
                    String collateValue = lexer.stringVal();
                    if (lexer.token == Token.IDENTIFIER || lexer.token == Token.LITERAL_ALIAS || lexer.token == Token.LITERAL_CHARS) {
                        SQLIdentifierExpr identifierExpr = new SQLIdentifierExpr(ident);
                        identifierExpr.setCollate(collateValue);
                        lexer.nextToken();
                        sqlExpr = identifierExpr;
                    } else {
                        throw new ParserException("syntax error. " + lexer.info());
                    }
                } else if (hash_lower == FnvHash.Constants.TRY_CAST) {
                    accept(Token.LPAREN);
                    SQLCastExpr cast = new SQLCastExpr();
                    cast.setTry(true);
                    cast.setExpr(expr());
                    accept(Token.AS);
                    cast.setDataType(parseDataType(false));
                    accept(Token.RPAREN);

                    sqlExpr = cast;
                } else if (hash_lower == FnvHash.Constants.DATE
                        && (lexer.token == Token.LITERAL_CHARS || lexer.token == Token.VARIANT)
                        && (dialectFeatureEnabled(SQLDateExpr))) {
                    String literal = lexer.token == Token.LITERAL_CHARS ? lexer.stringVal() : "?";
                    lexer.nextToken();
                    SQLDateExpr dateExpr = new SQLDateExpr();
                    dateExpr.setValue(literal);
                    sqlExpr = dateExpr;
                } else if (hash_lower == FnvHash.Constants.TIMESTAMP
                        && (lexer.token == Token.LITERAL_CHARS || lexer.token == Token.VARIANT)
                        && dialectFeatureEnabled(SQLTimestampExpr)) {
                    SQLTimestampExpr dateExpr = new SQLTimestampExpr(lexer.stringVal());
                    lexer.nextToken();
                    sqlExpr = dateExpr;
                } else if (hash_lower == FnvHash.Constants.TIME
                        && (lexer.token == Token.LITERAL_CHARS || lexer.token == Token.VARIANT)) {
                    SQLTimeExpr dateExpr = new SQLTimeExpr(lexer.stringVal());
                    lexer.nextToken();
                    sqlExpr = dateExpr;
                } else if (hash_lower == FnvHash.Constants.TIME && lexer.token == Token.LITERAL_ALIAS) {
                    SQLTimeExpr dateExpr = new SQLTimeExpr(SQLUtils.normalize(lexer.stringVal()));
                    lexer.nextToken();
                    sqlExpr = dateExpr;
                } else if (hash_lower == FnvHash.Constants.DATETIME
                        && (lexer.token == Token.LITERAL_CHARS || lexer.token == Token.VARIANT)) {
                    SQLDateTimeExpr dateExpr = new SQLDateTimeExpr(lexer.stringVal());
                    lexer.nextToken();
                    sqlExpr = dateExpr;
                } else if (hash_lower == FnvHash.Constants.DATETIME && lexer.token == Token.LITERAL_ALIAS) {
                    SQLDateTimeExpr dateExpr = new SQLDateTimeExpr(SQLUtils.normalize(lexer.stringVal()));
                    lexer.nextToken();
                    sqlExpr = dateExpr;
                } else if (hash_lower == FnvHash.Constants.BOOLEAN && lexer.token == Token.LITERAL_CHARS) {
                    sqlExpr = new SQLBooleanExpr(Boolean.valueOf(lexer.stringVal()));
                    lexer.nextToken();
                } else if (hash_lower == FnvHash.Constants.DATE && lexer.token == Token.LITERAL_ALIAS) {
                    sqlExpr = new SQLDateExpr(lexer.stringVal());
                    lexer.nextToken();
                } else if (hash_lower == FnvHash.Constants.DATETIME && lexer.token == Token.LITERAL_ALIAS) {
                    sqlExpr = new SQLDateTimeExpr(lexer.stringVal());
                    lexer.nextToken();
                } else if (hash_lower == FnvHash.Constants.TIMESTAMP && lexer.token == Token.LITERAL_ALIAS) {
                    sqlExpr = new SQLTimestampExpr(lexer.stringVal());
                    lexer.nextToken();
                } else if (hash_lower == FnvHash.Constants.ARRAY && (lexer.token == Token.LBRACKET || lexer.token == Token.LT)) {
                    sqlExpr = parseArrayExpr(ident);
                } else {
                    sqlExpr = primaryIdentifierRest(hash_lower, ident);
                    if (sqlExpr == null) {
                        char c0 = ident.charAt(0);
                        if (c0 == '`' || c0 == '[' || c0 == '"') {
                            if (lexer.isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
                                ident = ident.substring(1, ident.length() - 1);
                            }
                            hash_lower = FnvHash.hashCode64(ident);
                        }
                        SQLIdentifierExpr identifierExpr = new SQLIdentifierExpr(ident, hash_lower);
                        if (sourceLine != -1) {
                            identifierExpr.setSource(sourceLine, sourceColumn);
                        }
                        sqlExpr = identifierExpr;
                    }
                }
                break;
            case NEW:
                throw new ParserException("TODO " + lexer.info());
            case LITERAL_INT:
                Number number = lexer.integerValue();
                lexer.nextToken();

                if (lexer.identifierEquals(FnvHash.Constants.BD)) {
                    SQLDecimalExpr decimalExpr = new SQLDecimalExpr();
                    decimalExpr.setValue(new BigDecimal(number.intValue()));
                    lexer.nextToken();
                    sqlExpr = decimalExpr;
                } else {
                    sqlExpr = new SQLIntegerExpr(number);
                }
                break;
            case LITERAL_FLOAT:
                sqlExpr = lexer.numberExpr();
                lexer.nextToken();

                if (lexer.identifierEquals(FnvHash.Constants.BD)) {
                    SQLDecimalExpr decimalExpr = new SQLDecimalExpr();
                    decimalExpr.setValue(new BigDecimal(sqlExpr.toString()));
                    lexer.nextToken();
                    sqlExpr = decimalExpr;
                }

                break;
            case LITERAL_CHARS: {
                sqlExpr = new SQLCharExpr(lexer.stringVal());
                sqlExpr = primaryLiteralCharsRest(sqlExpr);
                break;
            }
            case LITERAL_NCHARS:
                sqlExpr = new SQLNCharExpr(lexer.stringVal());
                lexer.nextToken();
                sqlExpr = primaryLiteralNCharsRest(sqlExpr);
                break;
            case VARIANT: {
                if (lexer.keepSourceLocation) {
                    lexer.computeRowAndColumn();
                }
                int line = lexer.getPosLine(), column = lexer.getPosColumn();
                String varName = lexer.stringVal();
                lexer.nextToken();

                if (varName.equals(":") && lexer.token == Token.IDENTIFIER && dialectFeatureEnabled(PrimaryVariantColon)) {
                    String part2 = lexer.stringVal();
                    lexer.nextToken();
                    varName += part2;
                }

                SQLVariantRefExpr varRefExpr = new SQLVariantRefExpr(varName);
                varRefExpr.setSource(line, column);
                if (varName.startsWith(":")) {
                    varRefExpr.setIndex(lexer.nextVarIndex());
                }
                if (varRefExpr.getName().equals("@")
                        && (lexer.token == Token.LITERAL_CHARS || (lexer.token == Token.VARIANT && lexer.stringVal().startsWith("@")))) {
                    varRefExpr.setName("@'" + lexer.stringVal() + "'");
                    lexer.nextToken();
                } else if (varRefExpr.getName().equals("@@") && lexer.token == Token.LITERAL_CHARS) {
                    varRefExpr.setName("@@'" + lexer.stringVal() + "'");
                    lexer.nextToken();
                }
                sqlExpr = varRefExpr;
            }
            break;
            case DEFAULT:
                sqlExpr = primaryDefaultRest();
                lexer.nextToken();
                break;
            case DUAL:
            case KEY:
//            case DISTINCT:
            case LIMIT:
            case SCHEMA:
            case COLUMN:
            case IF:
            case END:
            case COMMENT:
            case COMPUTE:
            case ENABLE:
            case DISABLE:
            case INITIALLY:
            case SEQUENCE:
            case USER:
            case EXPLAIN:
            case WITH:
            case GRANT:
            case REPLACE:
            case INDEX:
//            case MODEL:
            case PCTFREE:
            case INITRANS:
            case MAXTRANS:
            case SEGMENT:
            case CREATION:
            case IMMEDIATE:
            case DEFERRED:
            case STORAGE:
            case NEXT:
            case MINEXTENTS:
            case MAXEXTENTS:
            case MAXSIZE:
            case PCTINCREASE:
            case FLASH_CACHE:
            case CELL_FLASH_CACHE:
            case NONE:
            case LOB:
            case STORE:
            case ROW:
            case CHUNK:
            case CACHE:
            case NOCACHE:
            case LOGGING:
            case NOCOMPRESS:
            case KEEP_DUPLICATES:
            case EXCEPTIONS:
            case PURGE:
            case FULL:
            case TO:
            case IDENTIFIED:
            case PASSWORD:
            case BINARY:
            case WINDOW:
            case OFFSET:
            case SHARE:
            case START:
            case CONNECT:
            case MATCHED:
            case ERRORS:
            case REJECT:
            case UNLIMITED:
            case BEGIN:
            case EXCLUSIVE:
            case MODE:
            case ADVISE:
            case VIEW:
            case ESCAPE:
            case OVER:
            case ORDER:
            case CONSTRAINT:
            case TYPE:
            case OPEN:
            case REPEAT:
            case TABLE:
            case TRUNCATE:
            case EXCEPTION:
            case FUNCTION:
            case IDENTITY:
            case EXTRACT:
            case DESC:
            case DO:
            case GROUP:
            case MOD:
            case CONCAT:
            case PRIMARY:
            case PARTITION:
            case LEAVE:
            case CLOSE:
            case CONDITION:
            case OUT:
            case USE:
            case EXCEPT:
            case INTERSECT:
            case MERGE:
            case MINUS:
            case UNTIL:
            case TOP:
            case SHOW:
            case INOUT:
            case OUTER:
            case QUALIFY:
            case GET:
                sqlExpr = new SQLIdentifierExpr(lexer.stringVal());
                lexer.nextToken();
                break;
            case CASE:
                SQLCaseExpr caseExpr = new SQLCaseExpr();
                lexer.nextToken();
                if (lexer.token != Token.WHEN) {
                    caseExpr.setValueExpr(expr());
                }

                accept(Token.WHEN);
                SQLExpr testExpr = expr();
                accept(Token.THEN);
                SQLExpr valueExpr = expr();
                SQLCaseExpr.Item caseItem = new SQLCaseExpr.Item(testExpr, valueExpr);
                caseExpr.addItem(caseItem);

                while (lexer.token == Token.WHEN) {
                    lexer.nextToken();
                    testExpr = expr();
                    accept(Token.THEN);
                    valueExpr = expr();
                    caseItem = new SQLCaseExpr.Item(testExpr, valueExpr);
                    caseExpr.addItem(caseItem);
                }

                if (lexer.token == Token.ELSE) {
                    lexer.nextToken();
                    caseExpr.setElseExpr(expr());
                }

                accept(Token.END);

                sqlExpr = caseExpr;
                break;
            case EXISTS: {
                String strVal = lexer.stringVal();
                lexer.nextToken();
                switch (lexer.token) {
                    case COMMA:
                    case DOT:
                        sqlExpr = new SQLIdentifierExpr(strVal);
                        break;
                    default:
                        accept(Token.LPAREN);
                        sqlExpr = new SQLExistsExpr(createSelectParser().select());
                        accept(Token.RPAREN);
                        parseQueryPlanHint(sqlExpr);
                        break;
                }

                break;
            }
            case NOT:
                lexer.nextToken();
                if (lexer.token == Token.EXISTS) {
                    lexer.nextToken();
                    accept(Token.LPAREN);
                    SQLExistsExpr exists = new SQLExistsExpr(createSelectParser().select(), true);
                    accept(Token.RPAREN);

                    parseQueryPlanHint(exists);
                    if (lexer.token == Token.EQ) {
                        exists.setNot(false);
                        SQLExpr relational = this.relationalRest(exists);
                        sqlExpr = new SQLNotExpr(relational);
                    } else {
                        sqlExpr = exists;
                    }
                } else if (lexer.token == Token.LPAREN) {
                    lexer.nextToken();

                    SQLExpr notTarget = expr();
                    if (notTarget instanceof SQLBinaryOpExpr) {
                        ((SQLBinaryOpExpr) notTarget).setParenthesized(true);
                    }
                    if (notTarget instanceof SQLUnaryExpr) {
                        ((SQLUnaryExpr) notTarget).setParenthesized(true);
                    }
                    accept(Token.RPAREN);
                    notTarget = bitXorRest(notTarget);
                    notTarget = multiplicativeRest(notTarget);
                    notTarget = additiveRest(notTarget);
                    notTarget = shiftRest(notTarget);
                    notTarget = bitAndRest(notTarget);
                    notTarget = bitOrRest(notTarget);
                    notTarget = inRest(notTarget);
                    notTarget = relationalRest(notTarget);
                    sqlExpr = new SQLNotExpr(notTarget);

                    parseQueryPlanHint(sqlExpr);
                    return primaryRest(sqlExpr);
                } else {
                    SQLExpr restExpr = relational();
                    sqlExpr = new SQLNotExpr(restExpr);
                    parseQueryPlanHint(sqlExpr);
                }
                break;
            case FROM:
            case SELECT:
                sqlExpr = parseQueryExpr();
                break;
            case CAST:
                sqlExpr = parseCast();
                break;
            case SUB:
                lexer.nextToken();
                switch (lexer.token) {
                    case LITERAL_INT:
                        Number integerValue = lexer.integerValue();
                        if (integerValue instanceof Integer) {
                            int intVal = integerValue.intValue();
                            if (intVal == Integer.MIN_VALUE) {
                                integerValue = Long.valueOf(((long) intVal) * -1);
                            } else {
                                integerValue = Integer.valueOf(intVal * -1);
                            }
                        } else if (integerValue instanceof Long) {
                            long longVal = ((Long) integerValue).longValue();
                            if (longVal == 2147483648L) {
                                integerValue = Integer.valueOf((int) (((long) longVal) * -1));
                            } else {
                                integerValue = Long.valueOf(longVal * -1);
                            }
                        } else {
                            integerValue = ((BigInteger) integerValue).negate();
                        }
                        sqlExpr = new SQLIntegerExpr(integerValue);
                        lexer.nextToken();
                        break;
                    case LITERAL_FLOAT:
                        sqlExpr = lexer.numberExpr(true);
                        lexer.nextToken();
                        break;
                    case LITERAL_CHARS:
                    case LITERAL_ALIAS:
                        sqlExpr = primarySubLiteralAliasRest();
                        lexer.nextToken();

                        if (lexer.token == Token.LPAREN
                                || lexer.token == Token.LBRACKET
                                || lexer.token == Token.DOT) {
                            sqlExpr = primaryRest(sqlExpr);
                        }
                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Negative, sqlExpr);

                        break;
                    case QUES: {
                        SQLVariantRefExpr variantRefExpr = new SQLVariantRefExpr("?");
                        variantRefExpr.setIndex(lexer.nextVarIndex());
                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Negative, variantRefExpr);
                        lexer.nextToken();
                        break;
                    }
                    case PLUS:
                    case SUB:
                    case LPAREN:
                    case IDENTIFIER:
                    case BANG:
                    case CASE:
                    case CAST:
                    case NULL:
                    case INTERVAL:
                    case LBRACE:
                    case IF:
                    case CHECK:
                    case INDEX:
                    case PRIMARY:
                    case KEY:
                    case REPLACE:
                        sqlExpr = primary();

                        while (lexer.token == Token.HINT) {
                            lexer.nextToken();
                        }

                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Negative, sqlExpr);
                        break;
                    case VARIANT:
                        sqlExpr = primary();
                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Negative, sqlExpr);
                        break;
                    default:
                        throw new ParserException("TODO : " + lexer.info());
                }
                break;
            case PLUS:
                lexer.nextToken();
                switch (lexer.token) {
                    case LITERAL_CHARS:
                    case LITERAL_ALIAS:
                        sqlExpr = new SQLIdentifierExpr(lexer.stringVal());
                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Plus, sqlExpr);
                        lexer.nextToken();
                        break;
                    case QUES: {
                        SQLVariantRefExpr variantRefExpr = new SQLVariantRefExpr("?");
                        variantRefExpr.setIndex(lexer.nextVarIndex());
                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Plus, variantRefExpr);
                        lexer.nextToken();
                        break;
                    }
                    case PLUS:
                    case SUB:
                    case LITERAL_FLOAT:
                    case LITERAL_INT:
                    case LPAREN:
                    case IDENTIFIER:
                    case BANG:
                    case CASE:
                    case CAST:
                    case NULL:
                    case INTERVAL:
                    case LBRACE:
                    case IF:
                    case CHECK:
                    case REPLACE:
                        sqlExpr = primary();

                        while (lexer.token == Token.HINT) {
                            lexer.nextToken();
                        }

                        sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Plus, sqlExpr);
                        break;
                    default:
                        throw new ParserException("TODO " + lexer.info());
                }
                break;
            case TILDE:
                lexer.nextToken();
                SQLExpr unaryValueExpr = primary();
                SQLUnaryExpr unary = new SQLUnaryExpr(SQLUnaryOperator.Compl, unaryValueExpr);
                sqlExpr = unary;
                break;
            case QUES:
                primaryQues();
                SQLVariantRefExpr quesVarRefExpr = new SQLVariantRefExpr("?");
                quesVarRefExpr.setIndex(lexer.nextVarIndex());
                sqlExpr = quesVarRefExpr;
                break;
            case LEFT:
                sqlExpr = new SQLIdentifierExpr("LEFT");
                lexer.nextToken();
                break;
            case RIGHT:
                sqlExpr = new SQLIdentifierExpr("RIGHT");
                lexer.nextToken();
                break;
            case INNER:
                sqlExpr = new SQLIdentifierExpr("INNER");
                lexer.nextToken();
                break;
            case DATABASE:
                sqlExpr = new SQLIdentifierExpr("DATABASE");
                lexer.nextToken();
                break;
            case CASCADE:
                sqlExpr = new SQLIdentifierExpr("CASCADE");
                lexer.nextToken();
                break;
            case LOCK:
                sqlExpr = new SQLIdentifierExpr("LOCK");
                lexer.nextToken();
                break;
            case NULL:
                sqlExpr = new SQLNullExpr();
                lexer.nextToken();
                break;
            case BANG:
                lexer.nextToken();
                sqlExpr = new SQLUnaryExpr(
                        SQLUnaryOperator.Not,
                        primary()
                );
                break;
            case BANGBANG: {
                if (!dialectFeatureEnabled(PrimaryBangBangSupport)) {
                    throw new ParserException(lexer.info());
                }
                lexer.nextToken();
                sqlExpr = new SQLUnaryExpr(
                        SQLUnaryOperator.Not,
                        primary()
                );
                break;
            }
            case BANG_TILDE: {
                lexer.nextToken();
                SQLExpr bangExpr = primary();
                sqlExpr = new SQLUnaryExpr(SQLUnaryOperator.Not, new SQLUnaryExpr(SQLUnaryOperator.Compl, bangExpr));
                break;
            }
            case LITERAL_HEX:
                String hex = lexer.hexString();
                sqlExpr = new SQLHexExpr(hex);
                lexer.nextToken();
                break;
            case INTERVAL:
                sqlExpr = parseInterval();
                break;
            case COLON:
                lexer.nextToken();
                if (lexer.token == Token.LITERAL_ALIAS) {
                    sqlExpr = new SQLVariantRefExpr(":\"" + lexer.stringVal() + "\"");
                    lexer.nextToken();
                }
                break;
            case ANY:
                sqlExpr = parseAny();
                break;
            case SOME:
                sqlExpr = parseSome();
                break;
            case ALL:
                sqlExpr = parseAll();
                break;
            case LITERAL_ALIAS:
                sqlExpr = parseAliasExpr(lexer.stringVal());
                lexer.nextToken();
                break;
            case EOF:
                throw new EOFParserException();
            case TRUE:
                lexer.nextToken();
                sqlExpr = new SQLBooleanExpr(true);
                break;
            case FALSE:
                lexer.nextToken();
                sqlExpr = new SQLBooleanExpr(false);
                break;
            case BITS: {
                String strVal = lexer.stringVal();
                lexer.nextToken();
                sqlExpr = new SQLBinaryExpr(strVal);
                break;
            }
            case GLOBAL:
            case CONTAINS:
                sqlExpr = inRest(null);
                break;
            case SET: {
                Lexer.SavePoint savePoint = lexer.mark();
                lexer.nextToken();
                if (lexer.token == Token.SET && dialectFeatureEnabled(PrimaryTwoConsecutiveSet)) {
                    lexer.nextToken();
                }
                if (lexer.token() == Token.LPAREN) {
                    sqlExpr = new SQLIdentifierExpr("SET");
                } else if (lexer.token == Token.DOT) {
                    sqlExpr = new SQLIdentifierExpr("SET");
                    sqlExpr = this.primaryRest(sqlExpr);
                } else {
                    lexer.reset(savePoint);
                    throw new ParserException("ERROR. " + lexer.info());
                }
                break;
            }
            case LBRACE: {
                lexer.nextToken();
                if (lexer.identifierEquals(FnvHash.Constants.TS)) {
                    lexer.nextToken();
                    String literal = lexer.stringVal();
                    lexer.nextToken();
                    sqlExpr = new SQLTimestampExpr(literal);
                } else if (lexer.identifierEquals(FnvHash.Constants.D)
                        || lexer.identifierEquals(FnvHash.Constants.DATE)
                ) {
                    lexer.nextToken();
                    String literal = lexer.stringVal();
                    if (literal.length() > 2
                            && literal.charAt(0) == '"'
                            && literal.charAt(literal.length() - 1) == '"') {
                        literal = literal.substring(1, literal.length() - 1);
                    }
                    lexer.nextToken();
                    sqlExpr = new SQLDateExpr(literal);
                } else if (lexer.identifierEquals(FnvHash.Constants.T)) {
                    lexer.nextToken();
                    String literal = lexer.stringVal();
                    lexer.nextToken();
                    sqlExpr = new SQLTimeExpr(literal);
                } else if (lexer.identifierEquals(FnvHash.Constants.FN)) {
                    lexer.nextToken();
                    sqlExpr = this.expr();
                } else if (dialectFeatureEnabled(PrimaryLbraceOdbcEscape)) {
                    sqlExpr = this.expr(); // {identifier expr} is ODBC escape syntax and is accepted for ODBC compatibility.
                } else if (lexer.nextIf(LBRACE)) {
                    // support dbt style: {{ source(a,b) }} as identifier
                    sqlExpr = new SQLPatternExpr(this.expr());
                    // skip }
                    accept(RBRACE);
                } else {
                    throw new ParserException("ERROR. " + lexer.info());
                }
                accept(Token.RBRACE);
                break;
            }
            case VALUES:
            case TRIGGER:
            case FOR:
            case CHECK:
            case DELETE:
            case BY:
            case UPDATE:
            case LOOP:
            case LIKE:
            case UNION:
            case CREATE:
            case COMMA:
            case STAR:
            case DIV:
            case DISTRIBUTE:
            case UNIQUE:
            case PROCEDURE:
            case REFERENCES:
            case IS:
            case REVOKE:
            case DECLARE:
            case DROP:
            case RLIKE:
            case FOREIGN:
            case FETCH:
            case ASC:
            case CURSOR:
            case ALTER:
                sqlExpr = primaryCommon(sqlExpr);
                break;
            case AS: {
                sqlExpr = primaryAs(sqlExpr);
                break;
            }
            case DISTINCT:
                sqlExpr = primaryDistinct(sqlExpr);
                break;
            case BETWEEN:
            case IN:
                sqlExpr = primaryIn(sqlExpr);
                break;
            case LBRACKET:
                SQLArrayExpr arrayTmp = new SQLArrayExpr();
                lexer.nextToken();
                this.exprList(arrayTmp.getValues(), arrayTmp);
                accept(Token.RBRACKET);
                sqlExpr = arrayTmp;
                break;
            case ON:
                sqlExpr = primaryOn(sqlExpr);
                break;
            case COLONCOLON:
                sqlExpr = primaryColonColon(sqlExpr);
                break;
            case ARRAY: {
                SQLArrayExpr array = new SQLArrayExpr();
                array.setExpr(new SQLIdentifierExpr("ARRAY"));
                lexer.nextToken();
                if (lexer.nextIf(Token.LT)) {
                    SQLDataType sqlDataType = this.parseDataType();
                    array.setDataType(sqlDataType);
                    accept(Token.GT);
                }
                if (lexer.nextIf(Token.LBRACKET)) {
                    this.exprList(array.getValues(), array);
                    accept(Token.RBRACKET);
                } else {
                    throw new ParserException("Syntax error. " + lexer.info());
                }

                sqlExpr = array;
                break;
            }
            case LITERAL_TEXT_BLOCK:
                sqlExpr = new SQLCharExpr(lexer.stringVal());
                lexer.nextToken();
                break;
            default:
                throw new ParserException("ERROR. " + lexer.info());
        }

        SQLExpr expr = primaryRest(sqlExpr);

        if (beforeComments != null) {
            expr.addBeforeComment(beforeComments);
        }
        if (lexer.hasComment() && lexer.isKeepComments()) {
            // @todo 是否保留注释，暂时待定，因为保留的话，有20来个测试用例会失败 by lizongbo
            // expr.addAfterComment(lexer.readAndResetComments());
        }
        return expr;
    }

    protected SQLExpr parseArrayExpr(String ident) {
        SQLExpr sqlExpr;
        SQLArrayExpr array = new SQLArrayExpr();
        array.setExpr(new SQLIdentifierExpr(ident));
        if (lexer.nextIf(Token.LT)) {
            SQLDataType sqlDataType = this.parseDataType();
            array.setDataType(sqlDataType);
            accept(Token.GT);
        }

        if (lexer.nextIf(Token.LBRACKET)) {
            this.exprList(array.getValues(), array);
            accept(Token.RBRACKET);
        } else {
            throw new ParserException("Syntax error. " + lexer.info());
        }
        sqlExpr = array;
        return sqlExpr;
    }

    protected SQLExpr parseCast() {
        String castStr = lexer.stringVal();
        lexer.nextToken();

        if (lexer.token != Token.LPAREN) {
            return new SQLIdentifierExpr(castStr);
        } else {
            lexer.nextToken();
            SQLCastExpr cast = new SQLCastExpr();
            cast.setExpr(
                    expr());
            accept(Token.AS);
            cast.setDataType(
                    parseDataType(false));
            if (cast.getDataType() instanceof SQLArrayDataType) {
                SQLArrayDataType arrayDataType = (SQLArrayDataType) cast.getDataType();
                if (arrayDataType.getDbType() == null) {
                    arrayDataType.setDbType(dbType);
                }
                arrayDataType.setUsedForCast(true);
            }
            cast = parseCastFormat(cast);
            accept(Token.RPAREN);
            return cast;
        }
    }

    protected SQLCastExpr parseCastFormat(SQLCastExpr cast) {
        return cast;
    }

    protected SQLExpr parseQueryExpr() {
        return new SQLQueryExpr(
                createSelectParser()
                        .select());
    }

    protected SQLExpr parseAll() {
        SQLExpr sqlExpr;
        String str = lexer.stringVal();
        lexer.nextToken();
        switch (lexer.token) {
            case DOT:
            case SLASH:
            case EQ:
            case GT:
            case GTEQ:
            case LT:
            case LTEQ:
            case STAR:
            case DIV:
                return primaryRest(new SQLIdentifierExpr(str));
            case COMMA:
            case PLUS:
            case SUB:
            case RPAREN:
            case WHERE:
            case GROUP:
            case SEMI:
            case AS:
            case FROM:
            case ORDER:
            case LIMIT:
            case UNION:
                return new SQLIdentifierExpr(str);
            case IDENTIFIER:
                if (dialectFeatureEnabled(ParseAllIdentifier)) {
                    return new SQLIdentifierExpr(str);
                }
                break;
            default:
                break;
        }

        SQLAllExpr allExpr = new SQLAllExpr();

        accept(Token.LPAREN);
        if (lexer.token != Token.SELECT && lexer.token != Token.VALUES && lexer.token != Token.LPAREN) {
            SQLExpr expr = this.expr();
            SQLMethodInvokeExpr methodInvokeExpr = new SQLMethodInvokeExpr("ALL");
            methodInvokeExpr.addArgument(expr);
            while (lexer.token == Token.COMMA) {
                lexer.nextToken();
                methodInvokeExpr.addArgument(expr());
            }
            accept(Token.RPAREN);
            return methodInvokeExpr;
        }
        SQLSelect allSubQuery = createSelectParser().select();
        allExpr.setSubQuery(allSubQuery);
        accept(Token.RPAREN);

        allSubQuery.setParent(allExpr);

        sqlExpr = allExpr;
        return sqlExpr;
    }

    protected SQLExpr parseSome() {
        SQLExpr sqlExpr;

        String str = lexer.stringVal();
        lexer.nextToken();
        if (lexer.token != Token.LPAREN) {
            return new SQLIdentifierExpr(str);
        }
        lexer.nextToken();

        if (lexer.token != Token.SELECT && lexer.token != Token.VALUES && lexer.token != Token.LPAREN) {
            SQLExpr expr = this.expr();
            SQLMethodInvokeExpr methodInvokeExpr = new SQLMethodInvokeExpr("SOME");
            methodInvokeExpr.addArgument(expr);
            while (lexer.token == Token.COMMA) {
                lexer.nextToken();
                methodInvokeExpr.addArgument(expr());
            }
            accept(Token.RPAREN);
            return methodInvokeExpr;
        }

        SQLSomeExpr someExpr = new SQLSomeExpr();
        SQLSelect someSubQuery = createSelectParser().select();
        someExpr.setSubQuery(someSubQuery);
        accept(Token.RPAREN);

        someSubQuery.setParent(someExpr);

        sqlExpr = someExpr;
        return sqlExpr;
    }

    protected SQLExpr parseAny() {
        SQLExpr sqlExpr;
        lexer.nextToken();
        if (lexer.token == Token.LPAREN) {
            accept(Token.LPAREN);

            if (lexer.token != Token.SELECT && lexer.token != Token.VALUES && lexer.token != Token.LPAREN) {
                SQLExpr expr = this.expr();
                SQLMethodInvokeExpr methodInvokeExpr = new SQLMethodInvokeExpr("ANY");
                methodInvokeExpr.addArgument(expr);
                while (lexer.token == Token.COMMA) {
                    lexer.nextToken();
                    methodInvokeExpr.addArgument(expr());
                }
                accept(Token.RPAREN);
                return methodInvokeExpr;
            }

            SQLSelect anySubQuery = createSelectParser().select();
            SQLAnyExpr anyExpr = new SQLAnyExpr(anySubQuery);
            accept(Token.RPAREN);

            sqlExpr = anyExpr;
        } else {
            sqlExpr = new SQLIdentifierExpr("ANY");
        }
        return sqlExpr;
    }

    protected SQLExpr parseAliasExpr(String alias) {
        return new SQLIdentifierExpr(alias);
    }

    protected SQLExpr parseInterval() {
        String str = lexer.stringVal();
        accept(Token.INTERVAL);
        switch (lexer.token) {
            case COMMA:
            case IS:
            case BETWEEN:
            case IN:
            case RPAREN:
            case EQ:
            case BANGEQ:
            case LTGT:
            case LT:
            case LTEQ:
            case GT:
            case GTEQ:
            case STAR:
            case DIV:
            case SLASH:
            case DOT:
            case FROM:
            case ORDER:
            case THEN:
            case END:
            case AS:
            case UNION:
            case SEMI:
            case ASC:
            case DESC:
            case ELSE:
                return new SQLIdentifierExpr(str);
            case PLUS:
            case SUB: {
                break;
            }
            default:
                if (lexer.identifierEquals(FnvHash.Constants.GROUPING)) {
                    return new SQLIdentifierExpr(str);
                }
                break;
        }

        SQLExpr value = expr();

        if (value instanceof SQLCharExpr) {
            String literal = ((SQLCharExpr) value).getText();
            int space = literal.indexOf(' ');
            if (space != -1) {
                int intervalValue = Integer.valueOf(literal.substring(0, space));
                String unitStr = literal.substring(space + 1).toUpperCase();
                SQLIntervalUnit unit = SQLIntervalUnit.of(unitStr);
                return new SQLIntervalExpr(new SQLIntegerExpr(intervalValue), unit);
            }
        }

        if (lexer.token() != Token.IDENTIFIER) {
            throw new ParserException("Syntax error. " + lexer.info());
        }

        String unit = lexer.stringVal().toUpperCase();
        lexer.nextToken();

        SQLIntervalExpr intervalExpr = new SQLIntervalExpr();
        intervalExpr.setValue(value);
        intervalExpr.setUnit(SQLIntervalUnit.of(unit));

        return intervalExpr;
    }

    public SQLSelectParser createSelectParser() {
        return new SQLSelectParser(this);
    }

    public SQLExpr primaryRest(SQLExpr expr) {
        if (expr == null) {
            throw new ParserException("expr" + ", " + lexer.info());
        }

        Token token = lexer.token;
        if (token == Token.OF) {
            if (expr instanceof SQLIdentifierExpr) {
                long hashCode64 = ((SQLIdentifierExpr) expr).hashCode64();
                if (hashCode64 == FnvHash.Constants.CURRENT) {
                    lexer.nextToken();
                    SQLName cursorName = this.name();
                    return new SQLCurrentOfCursorExpr(cursorName);
                }
            }
        } else if (token == Token.FOR) {
            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr idenExpr = (SQLIdentifierExpr) expr;
                if (idenExpr.hashCode64() == FnvHash.Constants.NEXTVAL) {
                    lexer.nextToken();
                    SQLName seqName = this.name();
                    SQLSequenceExpr seqExpr = new SQLSequenceExpr(seqName, SQLSequenceExpr.Function.NextVal);
                    return seqExpr;
                } else if (idenExpr.hashCode64() == FnvHash.Constants.CURRVAL) {
                    lexer.nextToken();
                    SQLName seqName = this.name();
                    SQLSequenceExpr seqExpr = new SQLSequenceExpr(seqName, SQLSequenceExpr.Function.CurrVal);
                    return seqExpr;
                } else if (idenExpr.hashCode64() == FnvHash.Constants.PREVVAL) {
                    lexer.nextToken();
                    SQLName seqName = this.name();
                    SQLSequenceExpr seqExpr = new SQLSequenceExpr(seqName, SQLSequenceExpr.Function.PrevVal);
                    return seqExpr;
                }
            }
        } else if (token == Token.LBRACKET) {
            SQLArrayExpr array = new SQLArrayExpr();
            array.setExpr(expr);
            lexer.nextToken();
            this.exprList(array.getValues(), array);
            accept(Token.RBRACKET);
            expr = array;
            return primaryRest(expr);
        } else if (token == Token.DOT) {
            lexer.nextToken();

            if (expr instanceof SQLCharExpr) {
                String text = ((SQLCharExpr) expr).getText();
                expr = new SQLIdentifierExpr(text);
            }

            if (expr instanceof SQLDefaultExpr) {
                expr = new SQLIdentifierExpr(expr.toString());
            }

            expr = dotRest(expr);
            return primaryRest(expr);
        } else if (lexer.identifierEquals(FnvHash.Constants.SETS) //
                && expr.getClass() == SQLIdentifierExpr.class //
                && "GROUPING".equalsIgnoreCase(((SQLIdentifierExpr) expr).getName())) {
            SQLGroupingSetExpr groupingSets = new SQLGroupingSetExpr();
            lexer.nextToken();

            accept(Token.LPAREN);

            for (; ; ) {
                SQLExpr item;
                if (lexer.token == Token.LPAREN) {
                    lexer.nextToken();

                    if (lexer.token == Token.COMMA && dialectFeatureEnabled(PrimaryRestCommaAfterLparen)) {
                        lexer.nextToken();
                    }

                    SQLListExpr listExpr = new SQLListExpr();
                    this.exprList(listExpr.getItems(), listExpr);
                    item = listExpr;

                    accept(Token.RPAREN);
                } else {
                    item = this.expr();
                }

                item.setParent(groupingSets);
                groupingSets.addParameter(item);

                if (lexer.token == Token.RPAREN) {
                    break;
                }

                accept(Token.COMMA);
            }

            this.exprList(groupingSets.getParameters(), groupingSets);

            accept(Token.RPAREN);

            return groupingSets;
        } else if (lexer.token == Token.LITERAL_CHARS && expr instanceof SQLIdentifierExpr && ((SQLIdentifierExpr) expr).hashCode64() == FnvHash.Constants.DECIMAL) {
            expr = new SQLDecimalExpr(lexer.stringVal());
            lexer.nextToken();
        } else {
            if (lexer.token == Token.LPAREN &&
                    !(expr instanceof SQLIntegerExpr) && !(expr instanceof SQLHexExpr) && !(expr instanceof SQLVariantRefExpr)
            ) {
                SQLExpr method = methodRest(expr, true);
                if (lexer.token == Token.LBRACKET || lexer.token == Token.DOT) {
                    method = primaryRest(method);
                }
                return method;
            }
        }

        return expr;
    }

    protected SQLExpr parseExtract() {
        throw new ParserException("not supported.");
    }

    protected SQLExpr parsePosition() {
        throw new ParserException("not supported.");
    }

    protected SQLExpr parseMatch() {
        SQLMatchAgainstExpr matchAgainstExpr = new SQLMatchAgainstExpr();

        if (lexer.token() == Token.RPAREN) {
            lexer.nextToken();
        } else {
            exprList(matchAgainstExpr.getColumns(), matchAgainstExpr);
            accept(Token.RPAREN);
        }

        if (lexer.identifierEquals(FnvHash.Constants.AGAINST)) {
            lexer.nextToken();
        }

        accept(Token.LPAREN);
        SQLExpr against = primary();
        matchAgainstExpr.setAgainst(against);

        if (lexer.token() == Token.IN) {
            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.NATURAL)) {
                lexer.nextToken();
                acceptIdentifier("LANGUAGE");
                acceptIdentifier("MODE");
                if (lexer.token() == Token.WITH) {
                    lexer.nextToken();
                    acceptIdentifier("QUERY");
                    acceptIdentifier("EXPANSION");
                    matchAgainstExpr.setSearchModifier(SQLMatchAgainstExpr.SearchModifier.IN_NATURAL_LANGUAGE_MODE_WITH_QUERY_EXPANSION);
                } else {
                    matchAgainstExpr.setSearchModifier(SQLMatchAgainstExpr.SearchModifier.IN_NATURAL_LANGUAGE_MODE);
                }
            } else if (lexer.identifierEquals(FnvHash.Constants.BOOLEAN)) {
                lexer.nextToken();
                acceptIdentifier("MODE");
                matchAgainstExpr.setSearchModifier(SQLMatchAgainstExpr.SearchModifier.IN_BOOLEAN_MODE);
            } else {
                throw new ParserException("syntax error. " + lexer.info());
            }
        } else if (lexer.token() == Token.WITH) {
            lexer.nextToken();
            acceptIdentifier("QUERY");
            acceptIdentifier("EXPANSION");
            matchAgainstExpr.setSearchModifier(SQLMatchAgainstExpr.SearchModifier.WITH_QUERY_EXPANSION);
        }

        accept(Token.RPAREN);

        return primaryRest(matchAgainstExpr);
    }

    protected SQLExpr methodRestAllowIdentifierMethodSpecific(String methodName, long hash_lower, SQLMethodInvokeExpr methodInvokeExpr) {
        return null;
    }

    protected void methodRestUsing(SQLMethodInvokeExpr methodInvokeExpr) {
    }
    protected SQLExpr methodRest(SQLExpr expr, boolean acceptLPAREN) {
        if (acceptLPAREN) {
            accept(Token.LPAREN);
        }

        boolean distinct = false;
        if (lexer.token == Token.DISTINCT) {
            lexer.nextToken();
            distinct = true;

            if (lexer.token == Token.RPAREN || lexer.token == Token.COMMA) {
                throw new ParserException(lexer.info());
            }
        }

        String methodName = null;
        String aggMethodName = null;
        SQLMethodInvokeExpr methodInvokeExpr = null;
        SQLExpr owner = null;
        String trimOption = null;

        long hash_lower = 0L;
        if (expr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr identifierExpr = (SQLIdentifierExpr) expr;
            methodName = identifierExpr.getName();
            hash_lower = identifierExpr.nameHashCode64();

            if (allowIdentifierMethod) {
                if (hash_lower == FnvHash.Constants.TRIM) {
                    if (lexer.identifierEquals(FnvHash.Constants.LEADING)) {
                        trimOption = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.identifierEquals(FnvHash.Constants.BOTH)) {
                        trimOption = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.identifierEquals(FnvHash.Constants.TRAILING)) {
                        trimOption = lexer.stringVal();
                        lexer.nextToken();
                    }
                } else if (hash_lower == FnvHash.Constants.TRY_CAST) {
                    SQLCastExpr cast = new SQLCastExpr();
                    cast.setTry(true);
                    cast.setExpr(expr());
                    accept(Token.AS);
                    cast.setDataType(parseDataType(false));
                    accept(Token.RPAREN);
                    return cast;
                } else {
                    SQLExpr resultExpr = methodRestAllowIdentifierMethodSpecific(methodName, hash_lower, methodInvokeExpr);
                    if (resultExpr != null) {
                        return resultExpr;
                    }
                }
            }

            if (distinct) {
                aggMethodName = methodName;
            } else {
                aggMethodName = getAggregateFunction(hash_lower);
            }
        } else if (expr instanceof SQLPropertyExpr) {
            methodName = ((SQLPropertyExpr) expr).getSimpleName();
            aggMethodName = SQLUtils.normalize(methodName);
            hash_lower = FnvHash.fnv1a_64_lower(aggMethodName);
            aggMethodName = getAggregateFunction(hash_lower);

            owner = ((SQLPropertyExpr) expr).getOwner();
        } else if (expr instanceof SQLDefaultExpr) {
            methodName = "DEFAULT";
        } else if (expr instanceof SQLCharExpr) {
            methodName = ((SQLCharExpr) expr).getText();
            if (isAggregateFunction(methodName)) {
                aggMethodName = methodName;
            }
        } else if (expr instanceof SQLDbLinkExpr) {
            SQLDbLinkExpr dbLinkExpr = (SQLDbLinkExpr) expr;
            methodName = dbLinkExpr.toString();
        }

        if (aggMethodName != null) {
            SQLAggregateExpr aggregateExpr = parseAggregateExpr(methodName);
            if (distinct) {
                aggregateExpr.setOption(SQLAggregateOption.DISTINCT);
            }

            if (lexer.token == Token.COLONCOLON) {
                return primaryRest(aggregateExpr);
            }

            return primaryRest(aggregateExpr);
        }

        methodInvokeExpr = new SQLMethodInvokeExpr(methodName, hash_lower);
        if (lexer.keepSourceLocation) {
            int line, column;
            if (lexer.keepSourceLocation) {
                lexer.computeRowAndColumn();
            }
            if (expr instanceof SQLObjectImpl) {
                line = expr.getSourceLine();
                column = expr.getSourceColumn();
            } else {
                line = lexer.getPosLine();
                column = lexer.getPosColumn();
            }
            methodInvokeExpr.setSource(line, column);
        }
        if (owner != null) {
            methodInvokeExpr.setOwner(owner);
        }
        if (trimOption != null) {
            methodInvokeExpr.setTrimOption(trimOption);
        }

        Token token = lexer.token;
        if ("XMLSERIALIZE".equals(methodName) && lexer.identifierEquals("CONTENT")) {
            SQLExpr contentExpr = expr();
            methodInvokeExpr.setContent(contentExpr);
        }
        if ("XMLELEMENT".equals(methodName) && lexer.identifierEquals("NAME")) {
            Lexer.SavePoint mark = lexer.markOut();
            lexer.nextToken(); // Skip NAME if it is a keyword
            if (lexer.token != Token.IDENTIFIER) {
                // No other identifier name comes after NAME, so NAME itself is
                // the xml element name. Reset lexer to NAME
                lexer.reset(mark);
            }
        }
        if (token != Token.RPAREN && token != Token.FROM) {
            exprList(methodInvokeExpr.getArguments(), methodInvokeExpr);
            if (lexer.token == Token.RPAREN) {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();
                if (lexer.token == Token.LPAREN) {
                    //for clickhouse parametric functions
                    //处理类似偏函数的语法 func(x,y)(a,b,c)
                    lexer.nextToken();
                    SQLParametricMethodInvokeExpr parametricExpr =
                            new SQLParametricMethodInvokeExpr(methodName, hash_lower);
                    methodInvokeExpr.cloneTo(parametricExpr);
                    methodInvokeExpr = parametricExpr;
                    exprList(((SQLParametricMethodInvokeExpr) methodInvokeExpr).getSecondArguments(), methodInvokeExpr);
                } else {
                    lexer.reset(mark);
                }
            }
        }

        if (hash_lower == FnvHash.Constants.EXIST
                && methodInvokeExpr.getArguments().size() == 1
                && methodInvokeExpr.getArguments().get(0) instanceof SQLQueryExpr) {
            throw new ParserException("exists syntax error.");
        }

        if (lexer.token == Token.FROM) {
            lexer.nextToken();
            SQLExpr from = this.expr();
            methodInvokeExpr.setFrom(from);

            if (lexer.token == Token.FOR) {
                lexer.nextToken();
                SQLExpr forExpr = expr();
                methodInvokeExpr.setFor(forExpr);
            }
        }

        if (lexer.token == Token.USING || lexer.identifierEquals(FnvHash.Constants.USING)) {
            lexer.nextToken();
            SQLExpr using;
            if (lexer.token == Token.STAR) {
                lexer.nextToken();
                using = new SQLAllColumnExpr();
            } else if (lexer.token == Token.BINARY) {
                using = new SQLIdentifierExpr(lexer.stringVal());
                lexer.nextToken();
            } else {
                using = this.primary();
            }
            methodInvokeExpr.setUsing(using);
        }

        // mysql
        if (hash_lower == FnvHash.Constants.WEIGHT_STRING) {
            if (lexer.token == Token.AS) {
                lexer.nextToken();
                SQLDataType as = this.parseDataType();
                methodInvokeExpr.putAttribute("as", as);
            }

            if (lexer.identifierEquals(FnvHash.Constants.LEVEL)) {
                lexer.nextToken();
                List<SQLSelectOrderByItem> levels = new ArrayList<SQLSelectOrderByItem>();
                for (; ; ) {
                    SQLSelectOrderByItem level = this.parseSelectOrderByItem();
                    levels.add(level);
                    if (lexer.token == Token.COMMA) {
                        lexer.nextToken();
                        continue;
                    }
                    break;
                }
                methodInvokeExpr.putAttribute("levels", levels);
            }

            if (lexer.identifierEquals(FnvHash.Constants.REVERSE)) {
                lexer.nextToken();
                methodInvokeExpr.putAttribute("reverse", true);
            }
        }

        if (lexer.token == Token.AS || lexer.identifierEquals(FnvHash.Constants.AS)) {
            lexer.nextToken();
            final SQLExpr as = this.primary();
            methodInvokeExpr.setAs(as);
        }
        SQLAggregateExpr aggregateExpr = null;
        if (lexer.token == Token.ORDER) {
            lexer.nextToken();
            accept(Token.BY);

            aggregateExpr = new SQLAggregateExpr(methodName);
            aggregateExpr.getArguments().addAll(methodInvokeExpr.getArguments());

            SQLOrderBy orderBy = new SQLOrderBy();
            this.orderBy(orderBy.getItems(), orderBy);
            aggregateExpr.setOrderBy(orderBy);
        }

        accept(Token.RPAREN);

        methodRestUsing(methodInvokeExpr);

        if (lexer.identifierEquals(FnvHash.Constants.FILTER)) {
            if (aggregateExpr == null) {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();
                Token nextToken = lexer.token;
                lexer.reset(mark);

                if (nextToken == Token.LPAREN) {
                    aggregateExpr = new SQLAggregateExpr(methodName);
                    aggregateExpr.getArguments().addAll(methodInvokeExpr.getArguments());
                    filter(aggregateExpr);
                }
            } else {
                filter(aggregateExpr);
            }
        }

        if (lexer.token == Token.OVER) {
            if (aggregateExpr == null) {
                aggregateExpr = new SQLAggregateExpr(methodName);
                aggregateExpr.addArguments(methodInvokeExpr.getArguments());
            }
            over(aggregateExpr);
        }

        if (aggregateExpr != null) {
            return primaryRest(aggregateExpr);
        }

        if (lexer.token == Token.LPAREN) {
            return methodInvokeExpr;
        }

        return primaryRest(methodInvokeExpr);

        //throw new ParserException("not support token:" + lexer.token + ", " + lexer.info());
    }

    protected String doRestSpecific(SQLExpr expr) {
        return null;
    }

    protected void aliasedItems(List<SQLAliasedExpr> items, SQLObject parent) {
        while (true) {
            SQLAliasedExpr aliasedExpr = aliasedExpr();
            aliasedExpr.setParent(parent);
            items.add(aliasedExpr);

            if (lexer.nextIfComma()) {
                if (lexer.token() == Token.FROM || lexer.token() == Token.RPAREN) {
                    break;
                }
                continue;
            }
            break;
        }
    }

    public SQLAliasedExpr aliasedExpr() {
        return new SQLAliasedExpr(expr(), as());
    }

    protected SQLExpr dotRest(SQLExpr expr) {
        if (lexer.nextIf(STAR)) {
            SQLAllColumnExpr allColumnExpr = new SQLAllColumnExpr();
            allColumnExpr.setOwner(expr);
            if (lexer.nextIf(EXCEPT)) {
                accept(Token.LPAREN);
                List<SQLExpr> except = new ArrayList<>();
                this.exprList(except, allColumnExpr);
                allColumnExpr.setExcept(except);
                accept(Token.RPAREN);
            }
            if (lexer.nextIf(REPLACE)) {
                accept(Token.LPAREN);
                this.aliasedItems(allColumnExpr.getReplace(), allColumnExpr);
                accept(Token.RPAREN);
            }
            expr = allColumnExpr;
        } else {
            String name;
            long hash_lower = 0L;

            if (lexer.token == Token.IDENTIFIER) {
                name = lexer.stringVal();
                hash_lower = lexer.hashLCase;
                lexer.nextToken();

                if (hash_lower == FnvHash.Constants.NEXTVAL) {
                    expr = new SQLSequenceExpr((SQLName) expr, SQLSequenceExpr.Function.NextVal);
                    return primaryRest(expr);
                } else if (hash_lower == FnvHash.Constants.CURRVAL) {
                    expr = new SQLSequenceExpr((SQLName) expr, SQLSequenceExpr.Function.CurrVal);
                    return primaryRest(expr);
                } else if (hash_lower == FnvHash.Constants.PREVVAL) {
                    expr = new SQLSequenceExpr((SQLName) expr, SQLSequenceExpr.Function.PrevVal);
                    return primaryRest(expr);
                }
            } else if (lexer.token == Token.LITERAL_CHARS
                    || lexer.token == Token.LITERAL_ALIAS) {
                name = lexer.stringVal();
                lexer.nextToken();
            } else if (lexer.getKeywords().containsValue(lexer.token)) {
                name = lexer.stringVal();
                lexer.nextToken();
            } else if (lexer.token == Token.VARIANT && lexer.stringVal().startsWith("$")) {
                name = lexer.stringVal();
                lexer.nextToken();
            } else {
                name = doRestSpecific(expr);
                if (name == null) {
                    throw new ParserException("error : " + lexer.info());
                }
            }

            if (lexer.token == Token.LPAREN) {
                boolean aggregate = hash_lower == FnvHash.Constants.WM_CONCAT
                        && expr instanceof SQLIdentifierExpr
                        && ((SQLIdentifierExpr) expr).nameHashCode64() == FnvHash.Constants.WMSYS;
                expr = methodRest(expr, name, aggregate);
            } else {
                if (name.length() > 0 && name.charAt(0) == '`') {
                    if (lexer.isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
                        name = name.substring(1, name.length() - 1);
                    }
                    hash_lower = FnvHash.hashCode64(name);
                }

                expr = new SQLPropertyExpr(expr, name, hash_lower);
            }
        }

        expr = primaryRest(expr);
        return expr;
    }

    private SQLExpr methodRest(SQLExpr expr, String name, boolean aggregate) {
        lexer.nextToken();

        if (lexer.token == Token.DISTINCT) {
            lexer.nextToken();

            SQLAggregateExpr aggregateExpr = new SQLAggregateExpr(name, SQLAggregateOption.DISTINCT);
            aggregateExpr.setOwner(expr);

            if (lexer.token == Token.RPAREN) {
                lexer.nextToken();
            } else {
                if (lexer.token == Token.PLUS) {
                    aggregateExpr.getArguments().add(new SQLIdentifierExpr("+"));
                    lexer.nextToken();
                } else {
                    exprList(aggregateExpr.getArguments(), aggregateExpr);
                }
                accept(Token.RPAREN);
            }
            expr = aggregateExpr;
        } else if (aggregate) {
            SQLAggregateExpr methodInvokeExpr = new SQLAggregateExpr(name);
            methodInvokeExpr.setMethodName(expr.toString() + "." + name);
            if (lexer.token == Token.RPAREN) {
                lexer.nextToken();
            } else {
                if (lexer.token == Token.PLUS) {
                    methodInvokeExpr.addArgument(new SQLIdentifierExpr("+"));
                    lexer.nextToken();
                } else {
                    exprList(methodInvokeExpr.getArguments(), methodInvokeExpr);
                }
                accept(Token.RPAREN);
            }

            if (lexer.token == Token.OVER) {
                over(methodInvokeExpr);
            }

            expr = methodInvokeExpr;
        } else {
            SQLMethodInvokeExpr methodInvokeExpr = new SQLMethodInvokeExpr(name);
            methodInvokeExpr.setOwner(expr);
            if (lexer.token == Token.RPAREN) {
                lexer.nextToken();
            } else {
                if (lexer.token == Token.PLUS) {
                    methodInvokeExpr.addArgument(new SQLIdentifierExpr("+"));
                    lexer.nextToken();
                } else {
                    exprList(methodInvokeExpr.getArguments(), methodInvokeExpr);
                }
                accept(Token.RPAREN);
            }

            if (lexer.token == Token.OVER) {
                SQLAggregateExpr aggregateExpr = new SQLAggregateExpr(methodInvokeExpr.getMethodName());
                aggregateExpr.setOwner(methodInvokeExpr.getOwner());
                aggregateExpr.getArguments().addAll(methodInvokeExpr.getArguments());
                over(aggregateExpr);
                methodInvokeExpr = aggregateExpr;
            }

            expr = methodInvokeExpr;
        }
        return expr;
    }

    public final SQLExpr groupComparisionRest(SQLExpr expr) {
        return expr;
    }

    public final void names(Collection<SQLName> exprCol) {
        names(exprCol, null);
    }

    public final void names(Collection<SQLName> exprCol, SQLObject parent) {
        if (lexer.token == Token.RBRACE) {
            return;
        }

        if (lexer.token == Token.EOF) {
            return;
        }

        SQLName name = name();
        name.setParent(parent);
        exprCol.add(name);

        while (lexer.token == Token.COMMA) {
            lexer.nextToken();

            if (parent instanceof SQLLateralViewTableSource && lexer.token == Token.NULL) {
                name = new SQLIdentifierExpr(lexer.stringVal());
                lexer.nextToken();
            } else {
                name = name();
            }

            name.setParent(parent);
            exprCol.add(name);
        }
    }

    @Deprecated
    public final void exprList(Collection<SQLExpr> exprCol) {
        exprList(exprCol, null);
    }

    protected void exprListComma() {
        lexer.nextToken();
    }

    public final void exprList(Collection<SQLExpr> exprCol, SQLObject parent) {
        exprList(exprCol, parent, false);
    }
    public final void exprList(Collection<SQLExpr> exprCol, SQLObject parent, boolean isNestDataType) {
        if (lexer.token == Token.RPAREN
                || lexer.token == Token.RBRACKET
                || lexer.token == Token.SEMI) {
            return;
        }

        if (lexer.token == Token.EOF) {
            return;
        }

        for (; ; ) {
            SQLExpr expr;
            if ((lexer.token == Token.ROW || (isNestDataType && lexer.token == IDENTIFIER)) && parent instanceof SQLDataType) {
                SQLDataType dataType = this.parseDataType();
                expr = new SQLDataTypeRefExpr(dataType);
            } else {
                expr = expr();
            }

            if (expr != null) {
                expr.setParent(parent);
                exprCol.add(expr);
                // https://github.com/alibaba/druid/issues/5709
                if (lexer.hasComment()
                        && lexer.isKeepComments()
                        && lexer.getComments().size() == 1
                        && lexer.getComments().get(0).startsWith("--")) {
                    expr.addAfterComment(lexer.readAndResetComments());
                }
            }

            if (lexer.token == Token.COMMA) {
                exprListComma();
                continue;
            }
            break;
        }
    }

    public SQLIdentifierExpr identifier() {
        SQLName name = name();
        if (name instanceof SQLIdentifierExpr) {
            return (SQLIdentifierExpr) name;
        }
        throw new ParserException("identifier excepted, " + lexer.info());
    }

    protected String nameCommon() {
        throw new ParserException("illegal name, " + lexer.info());
    }

    public SQLName name() {
        String identName;
        long hash = 0;
        if (lexer.token == Token.LITERAL_ALIAS) {
            identName = lexer.stringVal();
            lexer.nextToken();
        } else if (lexer.token == Token.IDENTIFIER) {
            identName = lexer.stringVal();

            char c0 = identName.charAt(0);
            if (c0 != '[') {
                hash = lexer.hashLCase();
            }
            lexer.nextToken();
        } else if (lexer.token == Token.LITERAL_CHARS) {
            identName = '\'' + lexer.stringVal() + '\'';
            lexer.nextToken();
        } else if (lexer.token == Token.VARIANT) {
            identName = lexer.stringVal();
            lexer.nextToken();
        } else {
            switch (lexer.token) {
//                case MODEL:
                case MODE:
                case ERRORS:
                case NOWAIT:
                case COMMIT:
                case PCTFREE:
                case INITRANS:
                case MAXTRANS:
                case SEGMENT:
                case CREATION:
                case IMMEDIATE:
                case DEFERRED:
                case STORAGE:
                case NEXT:
                case MINEXTENTS:
                case MAXEXTENTS:
                case MAXSIZE:
                case PCTINCREASE:
                case FLASH_CACHE:
                case CELL_FLASH_CACHE:
                case NONE:
                case LOB:
                case STORE:
                case ROW:
                case CHUNK:
                case CACHE:
                case NOCACHE:
                case LOGGING:
                case NOCOMPRESS:
                case KEEP_DUPLICATES:
                case EXCEPTIONS:
                case PURGE:
                case INITIALLY:
                case END:
                case COMMENT:
                case ENABLE:
                case DISABLE:
                case SEQUENCE:
                case USER:
                case ANALYZE:
                case OPTIMIZE:
                case GRANT:
                case REVOKE:
                    // binary有很多含义，lexer识别了这个token，实际上应该当做普通IDENTIFIER
                case BINARY:
                case OVER:
                case ORDER:
                case DO:
                case INNER:
                case JOIN:
                case TYPE:
                case FUNCTION:
                case KEY:
                case UNIQUE:
                case SCHEMA:
                case INTERVAL:
                case EXPLAIN:
                case SET:
                case TABLESPACE:
                case PARTITION:
                case CLOSE:
                case INOUT:
                case GOTO:
                case DEFAULT:
                case FULLTEXT:
                case WITH:
                case ANY:
                case BEGIN:
                case CAST:
                case COMPUTE:
                case ESCAPE:
                case EXCEPT:
                case FULL:
                case INTERSECT:
                case MERGE:
                case MINUS:
                case OPEN:
                case SOME:
                case TRUNCATE:
                case UNTIL:
                case VIEW:
                case GROUP:
                case INDEX:
                case DESC:
                case ALL:
                case SHOW:
                case FOR:
                case LEAVE:
                case REPEAT:
                case LOOP:
                case IS:
                case LOCK:
                case REFERENCES:
                case EXCEPTION:
                    identName = lexer.stringVal();
                    lexer.nextToken();
                    break;
                case CONSTRAINT:
                case CHECK:
                case VALUES:
                case IN:
                case OUT:
                case LIMIT:
                case TRIGGER:
                case USE:
                case LIKE:
                case DISTRIBUTE:
                case DELETE:
                case UPDATE:
                case PROCEDURE:
                case LEFT:
                case RIGHT:
                case TABLE:
                case RLIKE:
                case CREATE:
                case PARTITIONED:
                case UNION:
                case PRIMARY:
                case TO:
                case DECLARE:
                case AS:
                case BY:
                case EXISTS:
                case FOREIGN:
                case ALTER:
                case ASC:
                case NULL:
                case CURSOR:
                case FETCH:
                case BITMAP:
                case NGRAMBF:
                case INVERTED:
                case DATABASE:
                    identName = nameCommon();
                    break;
                default:
                    throw new ParserException("illegal name, " + lexer.info());
            }
        }

        SQLName identifierExpr = null;
        if (lexer.isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
            if (identName.indexOf('.') == -1) {
                identName = SQLUtils.forcedNormalize(identName, dbType);
                hash = 0;
            } else {
                identifierExpr = (SQLName) primaryIdentifierRest(hash, identName);
            }
        }

        if (identifierExpr == null) {
            identifierExpr = new SQLIdentifierExpr(identName, hash);
        }
        if (lexer.keepSourceLocation) {
            lexer.computeRowAndColumn(identifierExpr);
        }

        SQLName name = identifierExpr;

        name = nameRest(name);

        return name;
    }

    public SQLName nameRest(SQLName name) {
        if (lexer.token == Token.DOT) {
            lexer.nextToken();

            if (lexer.token == Token.KEY) {
                name = new SQLPropertyExpr(name, "KEY");
                lexer.nextToken();
                return name;
            }

            if (lexer.token != Token.LITERAL_ALIAS
                    && lexer.token != Token.IDENTIFIER
                    && lexer.token != Token.VARIANT
                    && lexer.token != Token.LITERAL_CHARS
                    && (!lexer.getKeywords().containsValue(lexer.token))) {
                throw new ParserException("error, " + lexer.info());
            }

            String propertyName;
            if (lexer.token == Token.LITERAL_CHARS) {
                propertyName = '\'' + lexer.stringVal() + '\'';
            } else {
                propertyName = lexer.stringVal();
            }
            if (lexer.isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
                propertyName = SQLUtils.forcedNormalize(propertyName, dbType);
            }
            name = new SQLPropertyExpr(name, propertyName);
            lexer.nextToken();
            name = nameRest(name);
        }

        return name;
    }

    public boolean isAggregateFunction(String word) {
        long hash_lower = FnvHash.fnv1a_64_lower(word);
        return isAggregateFunction(hash_lower);
    }

    protected boolean isAggregateFunction(long hash_lower) {
        return Arrays.binarySearch(aggregateFunctionHashCodes, hash_lower) >= 0;
    }

    protected String getAggregateFunction(long hash_lower) {
        int index = Arrays.binarySearch(aggregateFunctionHashCodes, hash_lower);
        if (index < 0) {
            return null;
        }
        return aggregateFunctions[index];
    }

    protected SQLAggregateExpr parseAggregateExpr(String methodName) {
        SQLAggregateExpr aggregateExpr;
        if (lexer.token == Token.ALL) {
            Lexer.SavePoint mark = lexer.mark();
            lexer.nextToken();
            if (lexer.token == Token.DOT) {
                aggregateExpr = new SQLAggregateExpr(methodName);
                lexer.reset(mark);
            } else {
                aggregateExpr = new SQLAggregateExpr(methodName, SQLAggregateOption.ALL);
            }
        } else if (lexer.token == Token.DISTINCT) {
            aggregateExpr = new SQLAggregateExpr(methodName, SQLAggregateOption.DISTINCT);
            lexer.nextToken();
        } else if (lexer.identifierEquals(FnvHash.Constants.DEDUPLICATION)) { // just for nut
            aggregateExpr = new SQLAggregateExpr(methodName, SQLAggregateOption.DEDUPLICATION);
            lexer.nextToken();
        } else {
            aggregateExpr = new SQLAggregateExpr(methodName);
        }

        exprList(aggregateExpr.getArguments(), aggregateExpr);

        if (lexer.identifierEquals(FnvHash.Constants.IGNORE)) {
            lexer.nextToken();
            acceptIdentifier("NULLS");
            aggregateExpr.setIgnoreNulls(true);
        }

        if (lexer.identifierEquals(FnvHash.Constants.RESPECT)) {
            lexer.nextToken();
            acceptIdentifier("NULLS");
            aggregateExpr.setRespectNulls(true);

        }

        if (lexer.token != Token.RPAREN) {
            parseAggregateExprRest(aggregateExpr);
        }

        if (lexer.token == ORDER) {
            aggregateExpr.setOrderBy(
                    this.parseOrderBy());
        }

        if (lexer.nextIf(LIMIT)) {
            aggregateExpr.setLimit(
                    expr()
            );
        }

        accept(Token.RPAREN);

        if (lexer.identifierEquals(FnvHash.Constants.IGNORE)) {
            lexer.nextToken();
            acceptIdentifier("NULLS");
            aggregateExpr.setIgnoreNulls(true);
        }

        if (lexer.identifierEquals(FnvHash.Constants.RESPECT)) {
            lexer.nextToken();
            acceptIdentifier("NULLS");
            aggregateExpr.setRespectNulls(true);
        }

        if (lexer.nextIfIdentifier(FnvHash.Constants.WITHIN)) {
            accept(Token.GROUP);
            accept(Token.LPAREN);
            SQLOrderBy orderBy = this.parseOrderBy();
            aggregateExpr.setWithinGroup(true);
            aggregateExpr.setOrderBy(orderBy);
            accept(Token.RPAREN);
        }

        if (lexer.identifierEquals(FnvHash.Constants.FILTER)) {
            filter(aggregateExpr);
        }

        if (lexer.token == Token.OVER) {
            over(aggregateExpr);
        }

        return aggregateExpr;
    }

    protected void filter(SQLAggregateExpr x) {
        Lexer.SavePoint mark = lexer.mark();
        lexer.nextToken();

        switch (lexer.token) {
            case COMMA:
            case FROM:
                lexer.reset(mark);
                return;
            default:
                break;
        }

        accept(Token.LPAREN);
        accept(Token.WHERE);
        SQLExpr filter = this.expr();
        accept(Token.RPAREN);
        x.setFilter(filter);
    }

    protected void over(SQLAggregateExpr aggregateExpr) {
        lexer.nextToken();

        if (lexer.token != Token.LPAREN) {
            SQLName overRef = this.name();
            aggregateExpr.setOverRef(overRef);
            return;
        }

        SQLOver over = new SQLOver();
        over(over);
        aggregateExpr.setOver(over);
    }

    protected void over(SQLOver over) {
        lexer.nextToken();

        if (lexer.token == Token.PARTITION || lexer.identifierEquals("PARTITION")) {
            lexer.nextToken();
            accept(Token.BY);

            if (lexer.token == (Token.LPAREN)) {
                lexer.nextToken();
                exprList(over.getPartitionBy(), over);
                accept(Token.RPAREN);

                if (over.getPartitionBy().size() == 1) {
                    switch (lexer.token) {
                        case SLASH:
                        case DIV:
                        case STAR:
                        case PLUS:
                        case SUB:
                            SQLExpr first = this.exprRest(over.getPartitionBy().get(0));
                            first.setParent(over);
                            over.getPartitionBy().set(0, first);
                            break;
                        default:
                            break;
                    }

                    if (lexer.token == Token.COMMA) {
                        lexer.nextToken();
                        exprList(over.getPartitionBy(), over);
                    }
                }
            } else if (lexer.token == Token.ALL) {
                SQLName name = this.name();
                name.setParent(over);
                over.getPartitionBy().add(name);
                if (lexer.token == Token.COMMA) {
                    lexer.nextToken();
                    exprList(over.getPartitionBy(), over);
                }
            } else {
                exprList(over.getPartitionBy(), over);
            }
        }

        over.setOrderBy(parseOrderBy());
        over.setDistributeBy(parseDistributeBy());
        over.setSortBy(parseSortBy());
        over.setClusterBy(parseClusterBy());

        if (lexer.token == Token.OF) {
            lexer.nextToken();
            SQLName of = this.name();
            over.setOf(of);
        }

        SQLOver.WindowingType windowingType = null;
        if (lexer.identifierEquals(FnvHash.Constants.ROWS) || lexer.token == Token.ROWS) {
            windowingType = SQLOver.WindowingType.ROWS;

        } else if (lexer.identifierEquals(FnvHash.Constants.RANGE)) {
            windowingType = SQLOver.WindowingType.RANGE;
        }

        if (windowingType != null) {
            over.setWindowingType(windowingType);
            lexer.nextToken();

            if (lexer.token == Token.BETWEEN) {
                lexer.nextToken();

                if (lexer.token == Token.LITERAL_INT
                        || lexer.token == Token.LITERAL_FLOAT
                        || lexer.token == Token.LITERAL_CHARS
                        || lexer.token == Token.CAST
                ) {
                    SQLExpr betweenBegin = this.additive();
                    over.setWindowingBetweenBegin(betweenBegin);
                } else if (lexer.token == Token.IDENTIFIER) {
                    long hash = lexer.hashLCase();

                    if (hash != FnvHash.Constants.PRECEDING
                            && hash != FnvHash.Constants.FOLLOWING
                            && hash != FnvHash.Constants.CURRENT
                            && hash != FnvHash.Constants.UNBOUNDED) {
                        SQLExpr betweenBegin = this.primary();
                        over.setWindowingBetweenBegin(betweenBegin);
                    }
                } else if (lexer.token == Token.INTERVAL) {
                    SQLExpr betweenBegin = this.primary();
                    over.setWindowingBetweenBegin(betweenBegin);
                }

                final SQLOver.WindowingBound beginBound = parseWindowingBound();
                if (beginBound != null) {
                    over.setWindowingBetweenBeginBound(beginBound);
                }

                accept(Token.AND);

                if (lexer.token == Token.LITERAL_INT
                        || lexer.token == Token.LITERAL_FLOAT
                        || lexer.token == Token.LITERAL_CHARS
                ) {
                    SQLExpr betweenEnd = this.additive();
                    over.setWindowingBetweenEnd(betweenEnd);
                } else if (lexer.token == Token.INTERVAL) {
                    SQLExpr betweenBegin = this.additive();
                    over.setWindowingBetweenEnd(betweenBegin);
                } else if (lexer.token == Token.IDENTIFIER) {
                    long hash = lexer.hashLCase();

                    if (hash != FnvHash.Constants.PRECEDING
                            && hash != FnvHash.Constants.FOLLOWING
                            && hash != FnvHash.Constants.CURRENT
                            && hash != FnvHash.Constants.UNBOUNDED) {
                        SQLExpr betweenBegin = this.additive();
                        over.setWindowingBetweenEnd(betweenBegin);
                    }
                }

                final SQLOver.WindowingBound endBound = parseWindowingBound();
                if (endBound != null) {
                    over.setWindowingBetweenEndBound(endBound);
                }
            } else {
                if (lexer.token == Token.LITERAL_INT
                        || lexer.token == Token.LITERAL_FLOAT
                        || lexer.token == Token.LITERAL_CHARS
                        || lexer.token == Token.INTERVAL
                ) {
                    SQLExpr betweenBegin = this.additive();
                    over.setWindowingBetweenBegin(betweenBegin);
                } else if (lexer.token == Token.IDENTIFIER) {
                    long hash = lexer.hashLCase();

                    if (hash != FnvHash.Constants.PRECEDING
                            && hash != FnvHash.Constants.FOLLOWING
                            && hash != FnvHash.Constants.CURRENT
                            && hash != FnvHash.Constants.UNBOUNDED) {
                        SQLExpr betweenBegin = this.additive();
                        over.setWindowingBetweenBegin(betweenBegin);
                    }
                }

                final SQLOver.WindowingBound beginBound = parseWindowingBound();
                if (beginBound != null) {
                    over.setWindowingBetweenBeginBound(beginBound);
                }
            }
        }

        if (lexer.identifierEquals(FnvHash.Constants.EXCLUDE)) {
            lexer.nextToken();
            acceptIdentifier("CURRENT");
            acceptIdentifier("ROW");
            over.setExcludeCurrentRow(true);
        }

        accept(Token.RPAREN);
    }

    protected SQLOver.WindowingBound parseWindowingBound() {
        if (lexer.identifierEquals(FnvHash.Constants.PRECEDING)) {
            lexer.nextToken();
            return SQLOver.WindowingBound.PRECEDING;
        } else if (lexer.identifierEquals(FnvHash.Constants.FOLLOWING)) {
            lexer.nextToken();
            return SQLOver.WindowingBound.FOLLOWING;
        } else if (lexer.identifierEquals(FnvHash.Constants.CURRENT) || lexer.token == Token.CURRENT) {
            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.ROW)) {
                lexer.nextToken();
            } else {
                accept(Token.ROW);
            }
            return SQLOver.WindowingBound.CURRENT_ROW;
        } else if (lexer.identifierEquals(FnvHash.Constants.UNBOUNDED)) {
            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.PRECEDING)) {
                lexer.nextToken();
                return SQLOver.WindowingBound.UNBOUNDED_PRECEDING;
            } else {
                acceptIdentifier("FOLLOWING");
                return SQLOver.WindowingBound.UNBOUNDED_FOLLOWING;
            }
        }

        return null;
    }

    protected SQLAggregateExpr parseAggregateExprRest(SQLAggregateExpr aggregateExpr) {
        return aggregateExpr;
    }

    public SQLOrderBy parseOrderBy() {
        if (lexer.token == Token.ORDER) {
            SQLOrderBy orderBy = new SQLOrderBy();

            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.SIBLINGS)) {
                lexer.nextToken();
                orderBy.setSiblings(true);
            }

            accept(Token.BY);

            orderBy(orderBy.getItems(), orderBy);

            if (lexer.token == Token.ORDER) {
                throw new ParserException(lexer.info()); // dual order by
            }

            return orderBy;
        }

        return null;
    }

    public SQLZOrderBy parseZOrderBy() {
        if (lexer.identifierEquals("ZORDER")) {
            SQLZOrderBy orderBy = new SQLZOrderBy();

            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.SIBLINGS)) {
                lexer.nextToken();
                orderBy.setSibings(true);
            }

            accept(Token.BY);

            orderBy(orderBy.getItems(), orderBy);

            if (lexer.token == Token.ORDER) {
                throw new ParserException(lexer.info()); // dual order by
            }

            return orderBy;
        }

        return null;
    }

    public SQLOrderBy parseDistributeBy() {
        if (lexer.token == Token.DISTRIBUTE || lexer.identifierEquals("DISTRIBUTE")) {
            SQLOrderBy orderBy = new SQLOrderBy();

            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.SIBLINGS)) {
                lexer.nextToken();
                orderBy.setSiblings(true);
            }

            accept(Token.BY);

            orderBy(orderBy.getItems(), orderBy);

            if (lexer.token == Token.ORDER) {
                throw new ParserException(lexer.info()); // dual order by
            }

            return orderBy;
        }

        return null;
    }

    public SQLOrderBy parseSortBy() {
        if (lexer.token == Token.SORT || lexer.identifierEquals(FnvHash.Constants.SORT)) {
            SQLOrderBy orderBy = new SQLOrderBy();

            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.SIBLINGS)) {
                lexer.nextToken();
                orderBy.setSiblings(true);
            }

            accept(Token.BY);

            orderBy(orderBy.getItems(), orderBy);

            if (lexer.token == Token.ORDER) {
                throw new ParserException(lexer.info()); // dual order by
            }

            return orderBy;
        }

        return null;
    }

    public SQLOrderBy parseClusterBy() {
        if (lexer.identifierEquals(FnvHash.Constants.CLUSTER)) {
            SQLOrderBy orderBy = new SQLOrderBy();

            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.SIBLINGS)) {
                lexer.nextToken();
                orderBy.setSiblings(true);
            }

            accept(Token.BY);

            orderBy(orderBy.getItems(), orderBy);

            if (lexer.token == Token.ORDER) {
                throw new ParserException(lexer.info()); // dual order by
            }

            return orderBy;
        }

        return null;
    }

    public void orderBy(List<SQLSelectOrderByItem> items, SQLObject parent) {
        SQLSelectOrderByItem item = parseSelectOrderByItem();
        item.setParent(parent);
        items.add(item);
        while (lexer.nextIf(Token.COMMA) || (item.getExpr() instanceof SQLVariantRefExpr && lexer.token == IDENTIFIER)) {
            item = parseSelectOrderByItem();
            item.setParent(parent);
            items.add(item);
        }
    }

    public SQLSelectOrderByItem parseSelectOrderByItem() {
        SQLSelectOrderByItem item = new SQLSelectOrderByItem();

        setAllowIdentifierMethod(false);

        try {
            SQLExpr expr;
            if (lexer.token() == Token.LITERAL_ALIAS) {
                expr = name();
                expr = primaryRest(expr);
                expr = exprRest(expr);
            } else if (lexer.token == Token.LPAREN) {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();
                for (int i = 0; ; ++i) {
                    expr = this.expr();
                    if (lexer.token == Token.ASC) {
                        lexer.nextToken();
                        item.setType(SQLOrderingSpecification.ASC);
                        if (lexer.token == Token.COMMA) {
                            lexer.nextToken();
                            continue;
                        }
                        accept(Token.RPAREN);
                    } else if (lexer.token == Token.DESC) {
                        lexer.nextToken();
                        item.setType(SQLOrderingSpecification.DESC);
                        if (lexer.token == Token.COMMA) {
                            lexer.nextToken();
                            continue;
                        }
                        accept(Token.RPAREN);
                    } else {
                        if (i > 0 && lexer.token == Token.RPAREN) {
                            lexer.nextToken();
                            break;
                        }
                        lexer.reset(mark);
                        expr = expr();
                    }
                    break;
                }
            } else {
                expr = expr();
            }
            if (isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
                if (expr instanceof SQLPropertyExpr) {
                    SQLPropertyExpr propertyExpr = (SQLPropertyExpr) expr;
                    SQLExpr owner = propertyExpr.getOwner();
                    if (owner != null) {
                        String ownerStr = SQLUtils.toSQLString(owner);
                        if (ownerStr.length() > 1) {
                            ownerStr = StringUtils.removeNameQuotes(ownerStr);
                        }
                        propertyExpr.setOwner(ownerStr);
                    }
                    String name = propertyExpr.getName();
                    if (name.length() > 1) {
                        name = StringUtils.removeNameQuotes(name);
                        propertyExpr.setName(name);
                    }
                    expr = propertyExpr;
                }
            }
            item.setExpr(expr);
        } finally {
            setAllowIdentifierMethod(true);
        }

        if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
            lexer.nextToken();
            String collate = lexer.stringVal();
            item.setCollate(collate);
            lexer.nextToken();
            if (lexer.token == Token.DOT) {
                lexer.nextToken();
                String collateOther = lexer.stringVal();
                item.setCollate(collate + "." + collateOther);
                lexer.nextToken();
            }
        }
        if (lexer.token == Token.LITERAL_ALIAS) {
            SQLExpr name = this.expr();
            item.setOpclass(name);
        }
        if (lexer.token == Token.ASC) {
            lexer.nextToken();
            item.setType(SQLOrderingSpecification.ASC);
        } else if (lexer.token == Token.DESC) {
            lexer.nextToken();
            item.setType(SQLOrderingSpecification.DESC);
        }

        if (lexer.identifierEquals(FnvHash.Constants.NULLS)) {
            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.FIRST) || lexer.token == Token.FIRST) {
                lexer.nextToken();
                item.setNullsOrderType(SQLSelectOrderByItem.NullsOrderType.NullsFirst);
            } else if (lexer.identifierEquals(FnvHash.Constants.LAST)) {
                lexer.nextToken();
                item.setNullsOrderType(SQLSelectOrderByItem.NullsOrderType.NullsLast);
            } else {
                throw new ParserException("TODO " + lexer.info());
            }
        }

        if (lexer.token == Token.HINT) {
            item.setHint(this.parseHint());
        }

        return item;
    }

    protected void parseUpdateSetItemLbracket(SQLUpdateSetItem item) {
    }
    public SQLUpdateSetItem parseUpdateSetItem() {
        SQLUpdateSetItem item = new SQLUpdateSetItem();

        if (lexer.token == (Token.LPAREN)) {
            lexer.nextToken();
            SQLListExpr list = new SQLListExpr();
            this.exprList(list.getItems(), list);
            accept(Token.RPAREN);
            item.setColumn(list);
        } else {
            String identName;
            long hash;

            Token token = lexer.token();
            if (token == Token.IDENTIFIER) {
                identName = lexer.stringVal();
                hash = lexer.hashLCase();
            } else if (token == Token.LITERAL_CHARS) {
                identName = '\'' + lexer.stringVal() + '\'';
                hash = 0;
            } else {
                identName = lexer.stringVal();
                hash = 0;
            }
            lexer.nextTokenEq();
            SQLExpr expr = new SQLIdentifierExpr(identName, hash);
            while (lexer.token() == Token.DOT) {
                lexer.nextToken();
                String propertyName = lexer.stringVal();
                lexer.nextTokenEq();
                expr = new SQLPropertyExpr(expr, propertyName);
            }

            item.setColumn(expr);
        }

        if (lexer.token == Token.LBRACKET) {
            parseUpdateSetItemLbracket(item);
        }

        if (lexer.token == Token.COLONEQ) {
            lexer.nextTokenValue();
        } else if (lexer.token == Token.EQ) {
            lexer.nextTokenValue();
        } else {
            throw new ParserException("syntax error, expect EQ, actual " + lexer.token + " "
                    + lexer.info());
        }

        item.setValue(this.expr());
        return item;
    }

    public final SQLExpr bitAnd() {
        SQLExpr expr = shift();

        if (lexer.token == Token.AMP) {
            expr = bitAndRest(expr);
        }

        return expr;
    }

    public final SQLExpr bitAndRest(SQLExpr expr) {
        while (lexer.token == Token.AMP) {
            lexer.nextToken();
            SQLExpr rightExp = shift();
            expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.BitwiseAnd, rightExp, getDbType());
        }
        return expr;
    }

    public final SQLExpr bitOr() {
        SQLExpr expr = bitAnd();

        if (lexer.token == Token.BAR) {
            expr = bitOrRest(expr);
        }

        return expr;
    }

    public final SQLExpr bitOrRest(SQLExpr expr) {
        while (lexer.token == Token.BAR) {
            lexer.nextToken();
            SQLBinaryOperator op = SQLBinaryOperator.BitwiseOr;
            if (lexer.token == Token.BAR) {
                lexer.nextToken();
                op = SQLBinaryOperator.Concat;
            }
            SQLExpr rightExp = bitAnd();
            expr = new SQLBinaryOpExpr(expr, op, rightExp, getDbType());
            expr = bitAndRest(expr);
        }
        return expr;
    }

    public final SQLExpr inRest(SQLExpr expr) {
        boolean global = false;

        // for clickhouse
        Lexer.SavePoint globalMark = lexer.mark();
        if (lexer.token == Token.GLOBAL) {
            global = true;
            lexer.nextToken();
            // global not in logic
            if (lexer.token == Token.NOT) {
                lexer.nextToken();
                return notRationalRest(expr, true);
            }

        }

        if (lexer.token == Token.IN) {
            Lexer.SavePoint mark = lexer.mark();

            lexer.nextTokenLParen();

            if (lexer.token == Token.COMMA) {
                lexer.reset(mark);
                return expr;
            }

            SQLInListExpr inListExpr = new SQLInListExpr(expr);
            List<SQLExpr> targetList = inListExpr.getTargetList();
            if (lexer.token == Token.LPAREN) {
                lexer.nextTokenValue();

                List<SQLCommentHint> hints = null;

                if (lexer.token == Token.HINT) {
                    hints = this.parseHints();
                }

                if (lexer.token == Token.WITH) {
                    SQLSelect select = this.createSelectParser().select();
                    SQLInSubQueryExpr queryExpr = new SQLInSubQueryExpr(select);
                    queryExpr.setExpr(expr);
                    accept(Token.RPAREN);
                    return queryExpr;
                }

                if (lexer.token != Token.RPAREN) {
                    for (; ; ) {
                        SQLExpr item;
                        if (lexer.token == Token.LITERAL_INT) {
                            item = new SQLIntegerExpr(lexer.integerValue());
                            lexer.nextToken();
                            if (lexer.token != Token.COMMA && lexer.token != Token.RPAREN) {
                                item = this.primaryRest(item);
                                item = this.exprRest(item);
                            }
                        } else {
                            item = this.expr();
                        }

                        item.setParent(inListExpr);
                        targetList.add(item);

                        if (item instanceof SQLCharExpr
                                && lexer.token == Token.LITERAL_CHARS
                                && dialectFeatureEnabled(InRestSpecificOperation)
                        ) {
                            continue;
                        }

                        if (lexer.token == Token.COMMA) {
                            lexer.nextTokenValue();

                            if (lexer.token == Token.RPAREN && dialectFeatureEnabled(InRestSpecificOperation)) {
                                break;
                            }
                            continue;
                        }
                        break;
                    }

                    switch (lexer.token) {
                        case MINUS:
                        case EXCEPT:
                        case UNION: {
                            if (targetList.size() == 1
                                    && targetList.get(0) instanceof SQLQueryExpr) {
                                SQLQueryExpr queryExpr = (SQLQueryExpr) targetList.get(0);
                                SQLSelectQuery query = this.createSelectParser().queryRest(queryExpr.getSubQuery().getQuery(), true);
                                if (query != queryExpr.getSubQuery()) {
                                    queryExpr.getSubQuery().setQuery(query);
                                }

                                if (hints != null && hints.size() > 0) {
                                    queryExpr.getSubQuery().setHeadHint(hints.get(0));
                                }
                            }
                            break;
                        }
                        default:
                            break;
                    }
                }

                int line = lexer.line;
                accept(Token.RPAREN);
                if (line + 1 == lexer.line
                        && lexer.hasComment()
                        && lexer.getComments().get(0).startsWith("--")) {
                    inListExpr.addAfterComment(lexer.readAndResetComments());
                }
            } else {
                SQLExpr itemExpr = primary();
                itemExpr.setParent(inListExpr);
                targetList.add(itemExpr);
            }

            parseQueryPlanHint(inListExpr);
            expr = inListExpr;

            if (targetList.size() == 1) {
                SQLExpr targetExpr = targetList.get(0);
                if (targetExpr instanceof SQLQueryExpr) {
                    SQLInSubQueryExpr inSubQueryExpr = new SQLInSubQueryExpr();
                    inSubQueryExpr.setExpr(inListExpr.getExpr());
                    inSubQueryExpr.setSubQuery(((SQLQueryExpr) targetExpr).getSubQuery());

                    inSubQueryExpr.setHint(inListExpr.getHint());

                    if (global) {
                        inSubQueryExpr.setGlobal(true);
                    }

                    expr = inSubQueryExpr;
                }
            }
        } else if (lexer.token == Token.CONTAINS) {
            lexer.nextTokenLParen();

            SQLContainsExpr containsExpr = new SQLContainsExpr(expr);
            List<SQLExpr> targetList = containsExpr.getTargetList();
            if (lexer.token == Token.LPAREN) {
                lexer.nextTokenValue();

                if (lexer.token == Token.WITH) {
                    SQLSelect select = this.createSelectParser().select();
                    SQLInSubQueryExpr queryExpr = new SQLInSubQueryExpr(select);
                    queryExpr.setExpr(expr);
                    accept(Token.RPAREN);
                    return queryExpr;
                }

                for (; ; ) {
                    SQLExpr item;
                    if (lexer.token == Token.LITERAL_INT) {
                        item = new SQLIntegerExpr(lexer.integerValue());
                        lexer.nextToken();
                        if (lexer.token != Token.COMMA && lexer.token != Token.RPAREN) {
                            item = this.primaryRest(item);
                            item = this.exprRest(item);
                        }
                    } else {
                        item = this.expr();
                    }

                    item.setParent(containsExpr);
                    targetList.add(item);
                    if (lexer.token == Token.COMMA) {
                        lexer.nextTokenValue();
                        continue;
                    }
                    break;
                }

                accept(Token.RPAREN);
            } else {
                SQLExpr itemExpr = primary();
                itemExpr.setParent(containsExpr);
                targetList.add(itemExpr);
            }

            expr = containsExpr;
        } else {
            lexer.reset(globalMark);
        }

        return expr;
    }

    public final SQLExpr additive() {
        SQLExpr expr = multiplicative();

        if (lexer.token == Token.PLUS
                || lexer.token == Token.BARBAR
                || lexer.token == Token.CONCAT
                || lexer.token == Token.SUB) {
            expr = additiveRest(expr);
        }

        return expr;
    }

    public final SQLExpr additiveRest(SQLExpr expr) {
        Token token = lexer.token;

        SQLBinaryOperator operator;
        if (token == Token.PLUS) {
            lexer.nextToken();

            while (lexer.token == Token.HINT) {
                SQLCommentHint hint = parseHint();
                if (expr instanceof SQLObjectImpl) {
                    ((SQLObjectImpl) expr).setHint(hint);
                }
            }

            operator = SQLBinaryOperator.Add;
        } else if ((token == Token.BARBAR || token == Token.CONCAT)
                && (isEnabled(SQLParserFeature.PipesAsConcat) || dialectFeatureEnabled(AdditiveRestPipesAsConcat))) {
            lexer.nextToken();
            operator = SQLBinaryOperator.Concat;
        } else if (token == Token.SUB) {
            lexer.nextToken();
            operator = SQLBinaryOperator.Subtract;
        } else {
            return expr;
        }

        SQLExpr rightExp = multiplicative();
        return additiveRest(
                new SQLBinaryOpExpr(expr, operator, rightExp, dbType)
        );
    }

    public final SQLExpr shift() {
        SQLExpr expr = additive();
        if (lexer.token == Token.LTLT || lexer.token == Token.GTGT || lexer.token == Token.GTGTGT) {
            expr = shiftRest(expr);
        }
        return expr;
    }

    public SQLExpr shiftRest(SQLExpr expr) {
        SQLBinaryOperator operator = null;
        if (lexer.token == Token.LTLT) {
            lexer.nextToken();
            operator = SQLBinaryOperator.LeftShift;
        } else if (lexer.token == Token.GTGT) {
            lexer.nextToken();
            operator = SQLBinaryOperator.RightShift;
        } else if (lexer.token == Token.GTGTGT) {
            lexer.nextToken();
            operator = SQLBinaryOperator.RightShiftUnsigned;
        }

        if (operator != null) {
            SQLExpr rightExp = additive();
            expr = new SQLBinaryOpExpr(expr, operator, rightExp, dbType);
            expr = shiftRest(expr);
        }

        return expr;
    }

    public SQLExpr and() {
        SQLExpr expr = relational();
        if (lexer.token == Token.AND || lexer.token == Token.AMPAMP) {
            expr = andRest(expr);
        }
        return expr;
    }

    //for ads
    public void parseQueryPlanHint(SQLExpr expr) {
        if (lexer.token == Token.HINT && (expr instanceof SQLInListExpr
                || expr instanceof SQLBinaryOpExpr
                || expr instanceof SQLInSubQueryExpr
                || expr instanceof SQLExistsExpr
                || expr instanceof SQLNotExpr
                || expr instanceof SQLBetweenExpr)) {
            String text = lexer.stringVal().trim();

            Lexer hintLex = SQLParserUtils.createLexer(text, dbType);
            hintLex.nextToken();

            //防止SQL注入
            if (hintLex.token == Token.PLUS) {
                if (expr instanceof SQLBinaryOpExpr) {
                    SQLBinaryOpExpr binaryOpExpr = (SQLBinaryOpExpr) expr;

                    SQLBinaryOperator operator = binaryOpExpr.getOperator();

                    if (operator == SQLBinaryOperator.BooleanAnd
                            || operator == SQLBinaryOperator.BooleanOr) {
                        if (binaryOpExpr.isParenthesized()) {
                            binaryOpExpr.setHint(new SQLCommentHint(text));
                        } else {
                            SQLExpr right = binaryOpExpr.getRight();
                            if (right instanceof SQLBinaryOpExpr
                                    || right instanceof SQLBetweenExpr) {
                                ((SQLExprImpl) right).setHint(new SQLCommentHint(text));
                            }
                        }
                    } else {
                        binaryOpExpr.setHint(new SQLCommentHint(text));
                    }
                } else if (expr instanceof SQLObjectImpl) {
                    ((SQLExprImpl) expr).setHint(new SQLCommentHint(text));
                } else {
                    throw new ParserException("TODO : " + lexer.info());
                }
                this.lexer.nextToken();
            }
        }
    }

    protected SQLBinaryOperator andRestGetAndOperator() {
        return SQLBinaryOperator.BooleanAnd;
    }
    public SQLExpr andRest(SQLExpr expr) {
        for (; ; ) {
            if (expr instanceof SQLBinaryOpExpr) {
                parseQueryPlanHint(expr);
            }

            Token token = lexer.token;

            if (token == Token.AND) {
                if (lexer.isKeepComments() && lexer.hasComment()) {
                    expr.addAfterComment(lexer.readAndResetComments());
                }

                lexer.nextToken();

                SQLExpr rightExp = relational();

                if (expr instanceof SQLBinaryOpExpr) {
                    parseQueryPlanHint(rightExp);
                }

                if (lexer.token == Token.AND
                        && lexer.isEnabled(SQLParserFeature.EnableSQLBinaryOpExprGroup)) {
                    SQLBinaryOpExprGroup group = new SQLBinaryOpExprGroup(SQLBinaryOperator.BooleanAnd, dbType);
                    group.add(expr);
                    group.add(rightExp);

                    if (lexer.isKeepComments() && lexer.hasComment()) {
                        rightExp.addAfterComment(lexer.readAndResetComments());
                    }

                    for (; ; ) {
                        lexer.nextToken();
                        SQLExpr more = relational();

                        if (more instanceof SQLBinaryOpExpr) {
                            parseQueryPlanHint(more);
                        }

                        group.add(more);

                        if (lexer.token == Token.AND) {
                            if (lexer.isKeepComments() && lexer.hasComment()) {
                                more.addAfterComment(lexer.readAndResetComments());
                            }

                            continue;
                        }
                        break;
                    }

                    expr = group;
                } else {
                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.BooleanAnd, rightExp, dbType);
                }
            } else if (token == Token.AMPAMP) {
                if (lexer.isKeepComments() && lexer.hasComment()) {
                    expr.addAfterComment(lexer.readAndResetComments());
                }

                lexer.nextToken();

                SQLExpr rightExp = relational();

                SQLBinaryOperator operator = andRestGetAndOperator();

                expr = new SQLBinaryOpExpr(expr, operator, rightExp, dbType);
            } else if (token == Token.VARIANT) {
                String value = lexer.stringVal();
                lexer.nextToken();
                SQLExpr variantExpr = new SQLVariantRefExpr(value);
                if (lexer.token == Token.IN) {
                    variantExpr = inRest(variantExpr);
                }
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Blank, variantExpr, dbType);
            } else {
                break;
            }
        }

        return expr;
    }

    public SQLExpr xor() {
        SQLExpr expr = and();
        if (lexer.token == Token.XOR) {
            expr = xorRest(expr);
        }
        return expr;
    }

    public SQLExpr xorRest(SQLExpr expr) {
        for (; ; ) {
            if (lexer.token == Token.XOR) {
                lexer.nextToken();
                SQLExpr rightExp = and();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.BooleanXor, rightExp, dbType);
            } else {
                break;
            }
        }

        return expr;
    }

    public SQLExpr or() {
        SQLExpr expr = xor();
        if (lexer.token == Token.OR || lexer.token == Token.BARBAR) {
            expr = orRest(expr);
        }
        return expr;
    }

    protected SQLBinaryOperator orRestGetOrOperator() {
        return SQLBinaryOperator.Concat;
    }
    public SQLExpr orRest(SQLExpr expr) {
        for (; ; ) {
            if (lexer.token == Token.OR) {
                if (lexer.isKeepComments() && lexer.hasComment()) {
                    expr.addAfterComment(lexer.readAndResetComments());
                }

                lexer.nextToken();
                SQLExpr rightExp = xor();

                if (lexer.token == Token.OR
                        && lexer.isEnabled(SQLParserFeature.EnableSQLBinaryOpExprGroup)) {
                    SQLBinaryOpExprGroup group = new SQLBinaryOpExprGroup(SQLBinaryOperator.BooleanOr, dbType);
                    group.add(expr);
                    group.add(rightExp);

                    if (lexer.isKeepComments() && lexer.hasComment()) {
                        rightExp.addAfterComment(lexer.readAndResetComments());
                    }

                    for (; ; ) {
                        lexer.nextToken();
                        SQLExpr more = xor();
                        group.add(more);
                        if (lexer.token == Token.OR) {
                            if (lexer.isKeepComments() && lexer.hasComment()) {
                                more.addAfterComment(lexer.readAndResetComments());
                            }

                            continue;
                        }
                        break;
                    }

                    expr = group;
                } else {
                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.BooleanOr, rightExp, dbType);
                }
            } else if (lexer.token == Token.BARBAR) {
                lexer.nextToken();
                SQLExpr rightExp = xor();

                SQLBinaryOperator op = orRestGetOrOperator();

                expr = new SQLBinaryOpExpr(expr, op, rightExp, dbType);
            } else if (lexer.token == Token.VARIANT) {
                String value = lexer.stringVal();
                lexer.nextToken();
                SQLExpr variantExpr = new SQLVariantRefExpr(value);
                if (lexer.token == Token.IN) {
                    variantExpr = inRest(variantExpr);
                }
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Blank, variantExpr, dbType);
            } else {
                break;
            }
        }

        return expr;
    }

    public SQLExpr relational() {
        SQLExpr expr = bitOr();

        return relationalRest(expr);
    }

    protected SQLExpr relationalRestQues(SQLExpr expr) {
        return expr;
    }

    protected SQLExpr relationalRestBang(SQLExpr expr) {
        return expr;
    }

    protected SQLExpr relationalRestEqeq(SQLExpr expr) {
        lexer.nextToken();
        return new SQLBinaryOpExpr(expr, SQLBinaryOperator.EqEq, expr());
    }

    protected SQLExpr relationalRestTilde(SQLExpr expr) {
        return expr;
    }

    protected SQLExpr relationalRestTildeStar(SQLExpr expr) {
        return expr;
    }
    protected SQLExpr relationalRestBangTilde(SQLExpr expr) {
        return expr;
    }

    protected SQLExpr relationalRestBangTildeStar(SQLExpr expr) {
        return expr;
    }

    protected SQLExpr relationalRestTildeEq(SQLExpr expr) {
        return expr;
    }

    protected SQLExpr relationalRestIdentifierSimilar(SQLExpr expr) {
        return expr;
    }
    public SQLExpr relationalRest(SQLExpr expr) {
        final SQLExpr initExpr = expr;
        SQLExpr rightExp = null;

        Token token = lexer.token;

        switch (token) {
            case EQ: {
                lexer.nextToken();
                try {
                    rightExp = bitOr();
                } catch (EOFParserException e) {
                    throw new ParserException("EOF, " + expr + "=", e);
                }

                if (lexer.token == Token.COLONEQ) {
                    lexer.nextToken();
                    SQLExpr colonExpr = expr();
                    rightExp = new SQLBinaryOpExpr(rightExp, SQLBinaryOperator.Assignment, colonExpr, dbType);
                }

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Equality, rightExp, dbType);
            }
            break;
            case IS: {
                lexer.nextTokenNotOrNull();

                SQLBinaryOperator op;
                if (lexer.token == Token.NOT) {
                    op = SQLBinaryOperator.IsNot;
                    lexer.nextTokenNotOrNull();
                } else {
                    op = SQLBinaryOperator.Is;
                }

                if (lexer.identifierEquals(FnvHash.Constants.JSON)) {
                    lexer.nextToken();

                    String name = "JSON";
                    if (lexer.identifierEquals(FnvHash.Constants.VALUE)) {
                        lexer.nextToken();
                        name = "JSON VALUE";
                    } else if (lexer.identifierEquals(FnvHash.Constants.OBJECT)) {
                        lexer.nextToken();
                        name = "JSON OBJECT";
                    } else if (lexer.identifierEquals(FnvHash.Constants.ARRAY)) {
                        lexer.nextToken();
                        name = "JSON ARRAY";
                    } else if (lexer.identifierEquals(FnvHash.Constants.SCALAR)) {
                        lexer.nextToken();
                        name = "JSON SCALAR";
                    }

                    rightExp = new SQLIdentifierExpr(name);
                } else if (lexer.token == Token.DISTINCT) {
                    lexer.nextToken();
                    accept(Token.FROM);

                    if (op == SQLBinaryOperator.Is) {
                        op = SQLBinaryOperator.IsDistinctFrom;
                    } else {
                        op = SQLBinaryOperator.IsNotDistinctFrom;
                    }
                    rightExp = bitOr();
                } else {
                    rightExp = primary();
                }
                expr = new SQLBinaryOpExpr(expr, op, rightExp, dbType);
            }
            break;
            case EQGT: {
                lexer.nextToken();
                rightExp = expr();
                String argumentName = ((SQLIdentifierExpr) expr).getName();
                expr = new OracleArgumentExpr(argumentName, rightExp);
            }
            break;
            case BANGEQ:
            case CARETEQ: {
                lexer.nextToken();
                rightExp = bitOr();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotEqual, rightExp, dbType);
            }
            break;
            case COLONEQ: {
                lexer.nextToken();
                rightExp = expr();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Assignment, rightExp, dbType);
            }
            break;
            case LT: {
                SQLBinaryOperator op = SQLBinaryOperator.LessThan;

                lexer.nextToken();
                if (lexer.token == Token.EQ) {
                    lexer.nextToken();
                    op = SQLBinaryOperator.LessThanOrEqual;
                } else if (lexer.token == Token.LT) {
                    lexer.nextToken();
                    op = SQLBinaryOperator.LeftShift;
                    rightExp = additive();
                }

                if (rightExp == null) {
                    rightExp = bitOr();
                }

                expr = new SQLBinaryOpExpr(expr, op, rightExp, getDbType());
            }
            break;
            case LTEQ:
                lexer.nextToken();
                rightExp = bitOr();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.LessThanOrEqual, rightExp, getDbType());
                break;
            case LTLTLT:
                lexer.nextToken();
                rightExp = bitOr();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.LeftShiftUnsigned, rightExp, getDbType());
                break;
            case LTEQGT: {
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.LessThanOrEqualOrGreaterThan, rightExp, getDbType());
            }
            break;
            case GT: {
                SQLBinaryOperator op = SQLBinaryOperator.GreaterThan;

                lexer.nextToken();

                if (lexer.token == Token.EQ) {
                    lexer.nextToken();
                    op = SQLBinaryOperator.GreaterThanOrEqual;
                }

                if (lexer.token == Token.GT) {
                    lexer.nextToken();
                    op = SQLBinaryOperator.RightShift;
                    rightExp = additive();
                } else if (lexer.token == Token.GTGT) {
                    lexer.nextToken();
                    op = SQLBinaryOperator.RightShiftUnsigned;
                    rightExp = additive();
                }

                if (rightExp == null) {
                    rightExp = bitOr();
                }
                expr = new SQLBinaryOpExpr(expr, op, rightExp, dbType);
            }
            break;
            case GTEQ: {
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.GreaterThanOrEqual, rightExp, dbType);
            }
            break;
            case BANGLT: {
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotLessThan, rightExp, dbType);
            }
            break;
            case BANGGT:
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotGreaterThan, rightExp, dbType);
                break;
            case LTGT:
                lexer.nextToken();
                rightExp = bitOr();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.LessThanOrGreater, rightExp, dbType);
                break;
            case LIKE: {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextTokenValue();
                if (lexer.token == Token.COMMA) {
                    lexer.reset(mark);
                    return expr;
                }
                rightExp = bitOr();

                if (rightExp.getClass() == SQLIdentifierExpr.class) {
                    String name = ((SQLIdentifierExpr) rightExp).getName();
                    int length = name.length();
                    if (length > 1
                            && name.charAt(0) == name.charAt(length - 1)
                            && name.charAt(0) != '`'
                    ) {
                        rightExp = new SQLCharExpr(name.substring(1, length - 1));
                    }
                }

                // rightExp = relationalRest(rightExp);

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Like, rightExp, dbType);

                if (lexer.token == Token.ESCAPE) {
                    lexer.nextToken();
                    rightExp = primary();
                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Escape, rightExp, dbType);
                }
                break;
            }
            case ILIKE:
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.ILike, rightExp, dbType);
                break;
            case MONKEYS_AT_AT:
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.AT_AT, rightExp, dbType);
                break;
            case MONKEYS_AT_GT:
                lexer.nextToken();
                rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Array_Contains, rightExp, dbType);
                break;
            case LT_MONKEYS_AT:
                lexer.nextToken();
                rightExp = bitOr();

                rightExp = relationalRest(rightExp);

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Array_ContainedBy, rightExp, dbType);
                break;
            case QUES:
                expr = relationalRestQues(expr);
                break;
            case NOT:
                lexer.nextToken();
                expr = notRationalRest(expr, false);
                break;
            case BANG:
                expr = relationalRestBang(expr);
                break;
            case BETWEEN:
                lexer.nextToken();
                SQLExpr beginExpr = relational();
                accept(Token.AND);
                SQLExpr endExpr = relational();
                expr = new SQLBetweenExpr(expr, beginExpr, endExpr);
                parseQueryPlanHint(expr);
                break;
            case IN:
            case CONTAINS:
            case GLOBAL:
                expr = inRest(expr);
                break;
            case EQEQ:
                expr = relationalRestEqeq(expr);
                break;
            case TILDE:
                expr = relationalRestTilde(expr);
                break;
            case TILDE_STAR:
                expr = relationalRestTildeStar(expr);
                break;
            case BANG_TILDE:
                expr = relationalRestBangTilde(expr);
                break;
            case BANG_TILDE_STAR:
                expr = relationalRestBangTildeStar(expr);
                break;
            case TILDE_EQ:
                expr = relationalRestTildeEq(expr);
                break;
            case RLIKE: {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();
                switch (lexer.token) {
                    case COMMA:
                        lexer.reset(mark);
                        break;
                    default:
                        rightExp = bitOr();
                        expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.RLike, rightExp, dbType);
                        break;
                }
                break;
            }
            case IDENTIFIER:
                long hash = lexer.hashLCase;
                if (hash == FnvHash.Constants.SOUNDS) {
                    lexer.nextToken();
                    accept(Token.LIKE);

                    rightExp = bitOr();

                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.SoudsLike, rightExp, dbType);
                } else if (hash == FnvHash.Constants.REGEXP) {
                    lexer.nextToken();
                    rightExp = bitOr();

                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.RegExp, rightExp, dbType);
                } else if (hash == FnvHash.Constants.SIMILAR) {
                    expr = relationalRestIdentifierSimilar(expr);
                } else {
                    return expr;
                }
                break;
            case VARIANT:
                rightExp = new SQLVariantRefExpr(lexer.stringVal);
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Blank, rightExp, dbType);
                lexer.nextToken();
                return expr;
            default:
                return expr;
        }

        if (expr == initExpr) {
            return expr;
        }

        switch (lexer.token) {
            case BETWEEN:
            case IS:
            case EQ:
            case EQEQ:
            case IN:
            case CONTAINS:
            case BANG_TILDE_STAR:
            case TILDE_EQ:
            case LT:
            case LTEQ:
            case LTEQGT:
            case GT:
            case GTEQ:
            case LTGT:
            case BANGEQ:
            case LIKE:
            case NOT:
                expr = relationalRest(expr);
                break;
            default:
                break;
        }

        return expr;
    }

    public SQLExpr notRationalRest(SQLExpr expr, boolean global) {
        switch (lexer.token) {
            case LIKE:
                lexer.nextTokenValue();
                SQLExpr rightExp = bitOr();

                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotLike, rightExp, dbType);

                if (lexer.token == Token.ESCAPE) {
                    lexer.nextToken();
                    rightExp = bitOr();
                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.Escape, rightExp, dbType);
                }
                break;
            case IN:
                lexer.nextToken();

                SQLInListExpr inListExpr = new SQLInListExpr(expr, true);
                if (lexer.token == Token.LPAREN) {
                    lexer.nextToken();

                    exprList(inListExpr.getTargetList(), inListExpr);
                    expr = inListExpr;

                    switch (lexer.token) {
                        case MINUS:
                        case UNION: {
                            List<SQLExpr> targetList = inListExpr.getTargetList();
                            if (targetList.size() == 1
                                    && targetList.get(0) instanceof SQLQueryExpr) {
                                SQLQueryExpr queryExpr = (SQLQueryExpr) targetList.get(0);
                                queryExpr.getSubQuery().getQuery().setParenthesized(queryExpr.isParenthesized());
                                SQLSelectQuery query = this.createSelectParser().queryRest(queryExpr.getSubQuery().getQuery(), true);
                                if (query != queryExpr.getSubQuery()) {
                                    queryExpr.getSubQuery().setQuery(query);
                                }
                            }
                            break;
                        }
                        default:
                            break;
                    }

                    accept(Token.RPAREN);
                } else {
                    SQLExpr valueExpr = this.primary();
                    valueExpr.setParent(inListExpr);
                    inListExpr.getTargetList().add(valueExpr);
                    expr = inListExpr;
                }

                parseQueryPlanHint(inListExpr);

                if (inListExpr.getTargetList().size() == 1) {
                    SQLExpr targetExpr = inListExpr.getTargetList().get(0);
                    if (targetExpr instanceof SQLQueryExpr) {
                        SQLInSubQueryExpr inSubQueryExpr = new SQLInSubQueryExpr();
                        inSubQueryExpr.setNot(true);
                        inSubQueryExpr.setExpr(inListExpr.getExpr());
                        inSubQueryExpr.setSubQuery(((SQLQueryExpr) targetExpr).getSubQuery());
                        inSubQueryExpr.setGlobal(global);
                        expr = inSubQueryExpr;
                    }
                }

                break;
            case CONTAINS:
                lexer.nextToken();

                SQLContainsExpr containsExpr = new SQLContainsExpr(expr, true);
                if (lexer.token == Token.LPAREN) {
                    lexer.nextToken();

                    exprList(containsExpr.getTargetList(), containsExpr);
                    expr = containsExpr;

                    switch (lexer.token) {
                        case MINUS:
                        case UNION: {
                            List<SQLExpr> targetList = containsExpr.getTargetList();
                            if (targetList.size() == 1
                                    && targetList.get(0) instanceof SQLQueryExpr) {
                                SQLQueryExpr queryExpr = (SQLQueryExpr) targetList.get(0);
                                SQLSelectQuery query = this.createSelectParser().queryRest(queryExpr.getSubQuery().getQuery(), true);
                                if (query != queryExpr.getSubQuery()) {
                                    queryExpr.getSubQuery().setQuery(query);
                                }
                            }
                            break;
                        }
                        default:
                            break;
                    }

                    accept(Token.RPAREN);
                } else {
                    SQLExpr valueExpr = this.primary();
                    valueExpr.setParent(containsExpr);
                    containsExpr.getTargetList().add(valueExpr);
                    expr = containsExpr;
                }

                if (containsExpr.getTargetList().size() == 1) {
                    SQLExpr targetExpr = containsExpr.getTargetList().get(0);
                    if (targetExpr instanceof SQLQueryExpr) {
                        SQLInSubQueryExpr inSubQueryExpr = new SQLInSubQueryExpr();
                        inSubQueryExpr.setNot(true);
                        inSubQueryExpr.setExpr(containsExpr.getExpr());
                        inSubQueryExpr.setSubQuery(((SQLQueryExpr) targetExpr).getSubQuery());
                        expr = inSubQueryExpr;
                    }
                }

                break;
            case BETWEEN:
                lexer.nextToken();
                SQLExpr beginExpr = relational();
                accept(Token.AND);
                SQLExpr endExpr = relational();

                expr = new SQLBetweenExpr(expr, true, beginExpr, endExpr);
                break;
            case ILIKE:
                lexer.nextToken();
                rightExp = bitOr();

                return new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotILike, rightExp, dbType);
            case LPAREN:
                expr = this.primary();
                break;
            case RLIKE:
                lexer.nextToken();
                rightExp = bitOr();
                expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotRLike, rightExp, dbType);
                expr = relationalRest(expr);
                break;
            case IDENTIFIER:
                long hash = lexer.hashLCase();
                if (hash == FnvHash.Constants.REGEXP) {
                    lexer.nextToken();
                    rightExp = bitOr();
                    expr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.NotRegExp, rightExp, dbType);
                    expr = relationalRest(expr);
                }
                break;
            default:
                throw new ParserException("TODO " + lexer.info());
        }

        return expr;
    }

    public SQLDataType parseDataType() {
        return parseDataType(true);
    }

    protected SQLDataType parseDataTypeNested() {
        return null;
    }

    protected void parseDataTypeByte(StringBuilder typeName) {
    }

    protected void parseDataTypeDouble(StringBuilder typeName) {
    }

    protected void parseDataTypePrecision(StringBuilder typeName) {
    }
    protected void parseDataTypeComplex(StringBuilder typeName) {
    }

    protected SQLDataType parseDataTypeDate(StringBuilder typeName, int sourceLine, int sourceColumn) {
        SQLDataType dataType = new SQLDataTypeImpl(typeName.toString());
        dataType.setDbType(dbType);
        dataType.setSource(sourceLine, sourceColumn);
        return dataType;
    }
    public SQLDataType parseDataType(boolean restrict) {
        if (lexer.keepSourceLocation) {
            lexer.computeRowAndColumn();
        }
        int sourceLine = lexer.getPosLine(), sourceColumn = lexer.getPosColumn();

        Token token = lexer.token;
        if (token == Token.DEFAULT || token == Token.NOT || token == Token.NULL) {
            return null;
        }

        if (lexer.identifierEquals(FnvHash.Constants.ARRAY) || lexer.token() == Token.ARRAY) {
            return parseArrayDataType();
        }

        if (lexer.identifierEquals(FnvHash.Constants.MAP)) {
            lexer.nextToken();

            if (lexer.token == Token.LPAREN) { // presto
                lexer.nextToken();
                SQLDataType keyType = parseDataType();
                accept(Token.COMMA);
                SQLDataType valueType = parseDataType();
                accept(Token.RPAREN);
                return new SQLMapDataType(keyType, valueType, dbType);
            }

            accept(Token.LT);

            SQLDataType keyType = parseDataType();
            accept(Token.COMMA);
            SQLDataType valueType = parseDataType();
            if (lexer.token == Token.GTGT) {
                lexer.token = Token.GT;
            } else {
                accept(Token.GT);
            }

            return new SQLMapDataType(keyType, valueType, dbType);
        }

        if (lexer.identifierEquals(FnvHash.Constants.STRUCT)) {
            return parseDataTypeStruct();
        } else if (lexer.token == Token.TABLE) {
            lexer.nextToken();
            SQLTableDataType table = new SQLTableDataType();
            accept(Token.LPAREN);
            for (; ; ) {
                SQLColumnDefinition column;
                if (lexer.token == Token.STAR) {
                    lexer.nextToken();
                    column = new SQLColumnDefinition();
                    column.setName("*");
                    accept(Token.ANY);
                } else {
                    column = this.parseColumn();
                }
                column.setParent(table);
                table.getColumns().add(column);
                if (lexer.token == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }
                break;
            }
            accept(Token.RPAREN);
            return table;
        } else if (lexer.identifierEquals(FnvHash.Constants.ROW) || lexer.token == Token.ROW) {
            lexer.nextToken();
            return parseDataTypeRow();
        } else if (lexer.identifierEquals(FnvHash.Constants.NESTED)) {
            SQLDataType dataType = parseDataTypeNested();
            if (dataType != null) {
                return dataType;
            }
        }

        if (lexer.identifierEquals(FnvHash.Constants.UNIONTYPE)) {
            lexer.nextToken();
            accept(Token.LT);

            SQLUnionDataType unionType = new SQLUnionDataType();
            for (; ; ) {
                SQLDataType item = this.parseDataType();
                unionType.add(item);
                if (lexer.token == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }

                break;
            }
            accept(Token.GT);
            return unionType;
        }

        if (lexer.identifierEquals(FnvHash.Constants.GENERATED)
                || lexer.identifierEquals(FnvHash.Constants.RENAME)) {
            return null;
        }

        SQLName typeExpr = name();
        final long typeNameHashCode = typeExpr.nameHashCode64();
        StringBuilder typeName = new StringBuilder(typeExpr.toString());

        if (typeNameHashCode == FnvHash.Constants.LONG
                && lexer.identifierEquals(FnvHash.Constants.BYTE)) {
            parseDataTypeByte(typeName);
        } else if (typeNameHashCode == FnvHash.Constants.DOUBLE) {
            parseDataTypeDouble(typeName);
            parseDataTypePrecision(typeName);
        }

        if (typeNameHashCode == FnvHash.Constants.UNSIGNED) {
            if (lexer.token == Token.IDENTIFIER) {
                typeName.append(' ').append(lexer.stringVal());
                lexer.nextToken();
            }
        } else if (typeNameHashCode == FnvHash.Constants.SIGNED) {
            if (lexer.token == Token.IDENTIFIER) {
                typeName.append(' ').append(lexer.stringVal());
                lexer.nextToken();
            }
        } else if (isCharType(typeNameHashCode)) {
            SQLCharacterDataType charType = new SQLCharacterDataType(typeName.toString());

            //for ads
            if (lexer.token == Token.LBRACKET) {
                SQLArrayDataType arrayDataType = new SQLArrayDataType(charType, dbType);
                lexer.nextToken();
                accept(Token.RBRACKET);
                arrayDataType.putAttribute("ads.arrayDataType", Boolean.TRUE);
                return arrayDataType;
            }

            if (lexer.token == Token.LPAREN) {
                lexer.nextToken();
                if (typeNameHashCode == FnvHash.Constants.ENUM) {
                    exprList(charType.getArguments(), charType);
                } else {
                    SQLExpr arg = this.expr();
                    arg.setParent(charType);
                    charType.addArgument(arg);
                }
                accept(Token.RPAREN);
            }

            charType = (SQLCharacterDataType) parseCharTypeRest(charType);

            if (lexer.token == Token.HINT) {
                List<SQLCommentHint> hints = this.parseHints();
                charType.setHints(hints);
            }

            if (lexer.identifierEquals(FnvHash.Constants.ARRAY)) {
                return parseDataTypeRest(charType);
            } else if (lexer.token == Token.LBRACKET) {
                return parseDataTypeRest(charType);
            }

            return charType;
        } else if (typeNameHashCode == FnvHash.Constants.DATE) {
            return parseDataTypeRest(parseDataTypeDate(typeName, sourceLine, sourceColumn));
        }

        if ("national".equalsIgnoreCase(typeName.toString()) &&
                (lexer.identifierEquals(FnvHash.Constants.CHAR)
                        || lexer.identifierEquals(FnvHash.Constants.VARCHAR))) {
            typeName.append(' ').append(lexer.stringVal());
            lexer.nextToken();

            SQLCharacterDataType charType = new SQLCharacterDataType(typeName.toString());

            if (lexer.token == Token.LPAREN) {
                lexer.nextToken();
                SQLExpr arg = this.expr();
                arg.setParent(charType);
                charType.addArgument(arg);
                accept(Token.RPAREN);
            }

            charType = (SQLCharacterDataType) parseCharTypeRest(charType);

            if (lexer.token == Token.HINT) {
                List<SQLCommentHint> hints = this.parseHints();
                charType.setHints(hints);
            }

            if (lexer.identifierEquals(FnvHash.Constants.ARRAY)) {
                return parseDataTypeRest(charType);
            }

            return charType;
        }

        if ("character".equalsIgnoreCase(typeName.toString()) && "varying".equalsIgnoreCase(lexer.stringVal())) {
            typeName.append(' ').append(lexer.stringVal());
            lexer.nextToken();
        }

        parseDataTypeComplex(typeName);

        SQLDataType dataType = new SQLDataTypeImpl(typeName.toString());
        dataType.setDbType(dbType);

        //for ads
        if (lexer.token == Token.LBRACKET) {
            dataType = new SQLArrayDataType(dataType, dbType);
            lexer.nextToken();
            if (lexer.token == Token.LITERAL_INT) {
                SQLExpr arg = this.expr();
                arg.setParent(dataType);
                dataType.getArguments().add(arg);
            }
            accept(Token.RBRACKET);
            dataType.putAttribute("ads.arrayDataType", Boolean.TRUE);
        }

        dataType.setSource(sourceLine, sourceColumn);
        return parseDataTypeRest(dataType);
    }

    protected SQLArrayDataType parseArrayDataType() {
        lexer.nextToken();

        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();
            SQLArrayDataType array = new SQLArrayDataType(null, dbType);
            this.exprList(array.getArguments(), array, true);
            accept(Token.RPAREN);
            return array;
        }

        accept(Token.LT);
        SQLDataType itemType = parseDataType();

        if (lexer.token == Token.GTGTGT) {
            lexer.token = Token.GTGT;
        } else if (lexer.token == Token.GTGT) {
            lexer.token = Token.GT;
        } else {
            accept(Token.GT);
        }

        SQLArrayDataType array = new SQLArrayDataType(itemType, dbType);

        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();
            this.exprList(array.getArguments(), array, true);
            accept(Token.RPAREN);
        }

        return array;
    }

    protected SQLStructDataType parseDataTypeStruct() {
        acceptIdentifier("STRUCT");

        SQLStructDataType struct = new SQLStructDataType(dbType);
        accept(Token.LT);
        for (; ; ) {
            SQLName name;
            switch (lexer.token) {
                case GROUP:
                case ORDER:
                case FROM:
                case TO:
                    name = new SQLIdentifierExpr(lexer.stringVal());
                    lexer.nextToken();
                    break;
                default:
                    name = this.name();
                    break;
            }

            accept(Token.COLON);
            SQLDataType dataType = this.parseDataType();
            SQLStructDataType.Field field = struct.addField(name, dataType);

            if (lexer.token == Token.COMMENT) {
                lexer.nextToken();
                SQLCharExpr chars = (SQLCharExpr) this.primary();
                field.setComment(chars.getText());
            }

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        if (lexer.token == Token.GTGTGT) {
            lexer.token = Token.GTGT;
        } else if (lexer.token == Token.GTGT) {
            lexer.token = Token.GT;
        } else {
            accept(Token.GT);
        }
        return struct;
    }

    protected SQLRowDataType parseDataTypeRow() {
        SQLRowDataType struct = new SQLRowDataType(dbType);
        accept(Token.LPAREN);

        for (; ; ) {
            SQLDataType dataType = null;
            Lexer.SavePoint mark = lexer.mark();
            SQLName name;
            switch (lexer.token) {
                case GROUP:
                case ORDER:
                case FROM:
                case TO:
                    name = new SQLIdentifierExpr(lexer.stringVal());
                    lexer.nextToken();
                    break;
                case ROW:
                    lexer.nextToken();
                    name = null;
                    dataType = this.parseDataTypeRow();
                    break;
                default:
                    name = this.name();
                    break;
            }

            if (lexer.token == Token.COMMA) {
                lexer.reset(mark);
                dataType = this.parseDataType();
                struct.addField(null, dataType);
                lexer.nextToken();
                continue;
            }

            if (lexer.token != Token.RPAREN) {
                dataType = this.parseDataType();
            }
            SQLStructDataType.Field field = struct.addField(name, dataType);

            if (lexer.token == Token.COMMENT) {
                lexer.nextToken();
                SQLCharExpr chars = (SQLCharExpr) this.primary();
                field.setComment(chars.getText());
            }

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.RPAREN);
        return struct;
    }

    protected void parseDataTypeParams(Collection<SQLExpr> exprCol, SQLDataType parent) {
        if (nestedDataType.contains(parent.getName().toLowerCase())) {
            exprList(exprCol, parent, true);
        } else {
            exprList(exprCol, parent);
        }
    }

    protected SQLDataType parseDataTypeRest(SQLDataType dataType) {
        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();
            parseDataTypeParams(dataType.getArguments(), dataType);
            accept(Token.RPAREN);
        }

        if (lexer.identifierEquals(FnvHash.Constants.PRECISION)
                && dataType.nameHashCode64() == FnvHash.Constants.DOUBLE) {
            lexer.nextToken();
            dataType.setName("DOUBLE PRECISION");
        }

        long nameHash = dataType.nameHashCode64();
        if (FnvHash.Constants.TIMESTAMP == nameHash || FnvHash.Constants.TIME == nameHash) {
            if (lexer.identifierEquals(FnvHash.Constants.WITHOUT)) {
                lexer.nextToken();
                acceptIdentifier("TIME");
                acceptIdentifier("ZONE");
                dataType.setWithTimeZone(false);
            } else if (lexer.token == Token.WITH) {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();
                if (lexer.identifierEquals(FnvHash.Constants.TIME)) {
                    lexer.nextToken();
                    acceptIdentifier("ZONE");
                    dataType.setWithTimeZone(true);
                } else {
                    lexer.reset(mark);
                }
            }
        } else if (FnvHash.Constants.INTERVAL == nameHash) {
            if (lexer.token == Token.IDENTIFIER) {
                String interval = dataType.getName();
                if (SQLIntervalUnit.of(lexer.stringVal) != null) {
                    interval += ' ' + lexer.stringVal;
                    lexer.nextToken();
                    if (lexer.nextIf(Token.TO)) {
                        interval += " to " + lexer.stringVal;
                        lexer.nextToken();
                        dataType.setName(interval);
                    }
                }
            }
        }

        return dataType;
    }

    protected boolean isCharType(String dataTypeName) {
        long hash = FnvHash.hashCode64(dataTypeName);
        return isCharType(hash);
    }

    protected boolean isCharType(long hash) {
        return hash == FnvHash.Constants.CHAR
                || hash == FnvHash.Constants.VARCHAR
                || hash == FnvHash.Constants.NCHAR
                || hash == FnvHash.Constants.NVARCHAR
                || hash == FnvHash.Constants.TINYTEXT
                || hash == FnvHash.Constants.TEXT
                || hash == FnvHash.Constants.MEDIUMTEXT
                || hash == FnvHash.Constants.LONGTEXT
                || hash == FnvHash.Constants.STRING
                || hash == FnvHash.Constants.ENUM;
    }

    protected SQLDataType parseCharTypeRest(SQLCharacterDataType charType) {
        if (lexer.token == Token.BINARY) {
            charType.setHasBinary(true);
            lexer.nextToken();
        }

        if (lexer.identifierEquals(FnvHash.Constants.CHARACTER)) {
            lexer.nextToken();

            accept(Token.SET);

            if (lexer.token != Token.IDENTIFIER
                    && lexer.token != Token.LITERAL_CHARS
                    && lexer.token != Token.BINARY) {
                throw new ParserException(lexer.info());
            }
            charType.setCharSetName(lexer.stringVal());
            lexer.nextToken();
        } else if (lexer.identifierEquals(FnvHash.Constants.CHARSET)) {
            lexer.nextToken();

            if (lexer.token != Token.IDENTIFIER
                    && lexer.token != Token.LITERAL_CHARS
                    && lexer.token != Token.BINARY) {
                throw new ParserException(lexer.info());
            }
            charType.setCharSetName(lexer.stringVal());
            lexer.nextToken();
        }

        if (lexer.token == Token.BINARY) {
            charType.setHasBinary(true);
            lexer.nextToken();
        }

        if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
            lexer.nextToken();

            if (lexer.token == Token.LITERAL_ALIAS
                    || lexer.token == Token.IDENTIFIER
                    || lexer.token == Token.LITERAL_CHARS) {
                StringBuilder collate = new StringBuilder(lexer.stringVal());
                lexer.nextToken();
                if (lexer.token == Token.DOT) {
                    lexer.nextToken();
                    if (lexer.token == Token.LITERAL_ALIAS
                            || lexer.token == Token.IDENTIFIER
                            || lexer.token == Token.LITERAL_CHARS) {
                        collate.append(".").append(lexer.stringVal());
                        lexer.nextToken();

                    } else {
                        throw new ParserException(lexer.info());
                    }
                }
                charType.setCollate(collate.toString());
            } else {
                throw new ParserException(lexer.info());
            }
        }

        return charType;
    }

    @Override
    public void accept(Token token) {
        if (lexer.token == token) {
            lexer.nextToken();
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("syntax error, expect ");
            sb.append((token.name != null ? token.name : token.toString()));
            sb.append(", actual ");
            sb.append((lexer.token.name != null ? lexer.token.name : lexer.token.toString()));
            sb.append(" ");
            sb.append(lexer.info());

            throw new ParserException(sb.toString());
        }
    }

    public SQLColumnDefinition parseColumn() {
        return parseColumn(null);
    }

    public SQLColumnDefinition parseColumn(SQLObject parent) {
        SQLColumnDefinition column = createColumnDefinition();
        column.setParent(parent);
        column.setDbType(dbType);
        if (Token.IF == lexer.token) {
            lexer.nextToken();
            accept(Token.NOT);
            accept(Token.EXISTS);
            column.setIfNotExists(true);
        }
        column.setName(
                name());

        final Token token = lexer.token;
        if (token != Token.SET //
                && token != Token.DROP
                && token != Token.PRIMARY
                && token != Token.RPAREN
                && token != Token.COMMA
                && token != Token.COMMENT
        ) {
            column.setDataType(
                    parseDataType());
        }
        return parseColumnRest(column);
    }

    public SQLColumnDefinition createColumnDefinition() {
        SQLColumnDefinition column = new SQLColumnDefinition();
        column.setDbType(dbType);
        return column;
    }

    protected SQLColumnDefinition parseColumnSpecific(SQLColumnDefinition column) {
        return column;
    }

    protected SQLExpr parseColumnRestDefault() {
        return bitOr();
    }

    protected void parseColumnCommentLiteralCharsRest(StringBuilder stringVal) {
    }
    public SQLColumnDefinition parseColumnRest(SQLColumnDefinition column) {
        switch (lexer.token) {
            case DEFAULT:
                lexer.nextToken();
                SQLExpr defaultExpr = parseColumnRestDefault();
                column.setDefaultExpr(defaultExpr);
                return parseColumnRest(column);
            case NOT: {
                lexer.nextToken();
                accept(Token.NULL);
                SQLNotNullConstraint notNull = new SQLNotNullConstraint();
                if (lexer.token == Token.HINT) {
                    List<SQLCommentHint> hints = this.parseHints();
                    notNull.setHints(hints);
                }
                column.addConstraint(notNull);
                return parseColumnRest(column);
            }
            case NULL:
                lexer.nextToken();
                column.getConstraints().add(new SQLNullConstraint());
                return parseColumnRest(column);
            case PRIMARY:
                lexer.nextToken();
                accept(Token.KEY);
                column.addConstraint(new SQLColumnPrimaryKey());
                return parseColumnRest(column);
            case UNIQUE:
                lexer.nextToken();
                if (lexer.token == Token.KEY) {
                    lexer.nextToken();
                }
                column.addConstraint(new SQLColumnUniqueKey());
                return parseColumnRest(column);
            case DISABLE:
                lexer.nextToken();
                if (lexer.stringVal.equalsIgnoreCase("novalidate")) {
                    column.setDisableNovalidate(true);
                }
                lexer.nextToken();
                return parseColumnRest(column);
            case KEY:
                lexer.nextToken();
                column.addConstraint(new SQLColumnPrimaryKey());
                return parseColumnRest(column);
            case REFERENCES: {
                SQLColumnReference ref = parseReference();
                column.addConstraint(ref);
                return parseColumnRest(column);
            }
            case CONSTRAINT:
                lexer.nextToken();

                SQLName name = this.name();

                if (lexer.token == Token.PRIMARY) {
                    lexer.nextToken();
                    accept(Token.KEY);
                    SQLColumnPrimaryKey pk = new SQLColumnPrimaryKey();
                    pk.setName(name);
                    column.addConstraint(pk);
                    return parseColumnRest(column);
                }

                if (lexer.token == Token.UNIQUE) {
                    lexer.nextToken();
                    SQLColumnUniqueKey uk = new SQLColumnUniqueKey();
                    uk.setName(name);

                    column.addConstraint(uk);
                    return parseColumnRest(column);
                }

                if (lexer.token == Token.REFERENCES) {
                    SQLColumnReference ref = parseReference();
                    ref.setName(name);
                    column.addConstraint(ref);
                    return parseColumnRest(column);
                }

                if (lexer.token == Token.NOT) {
                    lexer.nextToken();
                    accept(Token.NULL);
                    SQLNotNullConstraint notNull = new SQLNotNullConstraint();
                    notNull.setName(name);
                    column.addConstraint(notNull);
                    return parseColumnRest(column);
                }

                if (lexer.token == Token.CHECK) {
                    SQLColumnCheck check = parseColumnCheck();
                    check.setName(name);
                    check.setParent(column);
                    column.addConstraint(check);
                    return parseColumnRest(column);
                }

                if (lexer.token == Token.DEFAULT) {
                    lexer.nextToken();
                    SQLExpr expr = this.expr();
                    column.setDefaultExpr(expr);
                    return parseColumnRest(column);
                }

                throw new ParserException("TODO : " + lexer.info());
            case CHECK:
                SQLColumnCheck check = parseColumnCheck();
                column.addConstraint(check);
                return parseColumnRest(column);
            case IDENTIFIER:
                long hash = lexer.hashLCase();
                if (hash == FnvHash.Constants.AUTO_INCREMENT) {
                    lexer.nextToken();
                    column.setAutoIncrement(true);
                    //sequence parser
                    if (lexer.token == Token.BY) {
                        lexer.nextToken();
                        if (lexer.hashLCase() == FnvHash.Constants.GROUP) {
                            lexer.nextToken();
                            column.setSequenceType(AutoIncrementType.GROUP);
                            if (lexer.identifierEquals(FnvHash.Constants.UNIT)) {
                                lexer.nextToken();

                                if (lexer.identifierEquals(FnvHash.Constants.COUNT)) {
                                    lexer.nextToken();
                                    SQLExpr unitCount = primary();
                                    column.setUnitCount(unitCount);
                                }

                                if (lexer.token == Token.INDEX) {
                                    lexer.nextToken();
                                    SQLExpr unitIndex = primary();
                                    column.setUnitIndex(unitIndex);
                                }

                                if (lexer.hashLCase() == FnvHash.Constants.STEP) {
                                    lexer.nextToken();
                                    SQLExpr step = primary();
                                    column.setStep(step);
                                }
                            } else {
                                return parseColumnRest(column);
                            }
                        } else if (lexer.hashLCase() == FnvHash.Constants.TIME) {
                            lexer.nextToken();
                            column.setSequenceType(AutoIncrementType.TIME);
                            return parseColumnRest(column);
                        } else if (lexer.hashLCase() == FnvHash.Constants.SIMPLE) {
                            lexer.nextToken();
                            if (lexer.hashLCase() == FnvHash.Constants.WITH) {
                                lexer.nextToken();
                                if (lexer.hashLCase() == FnvHash.Constants.CACHE) {
                                    column.setSequenceType(AutoIncrementType.SIMPLE_CACHE);
                                } else {
                                    throw new ParserException("TODO : " + lexer.info());
                                }
                                lexer.nextToken();
                                return parseColumnRest(column);
                            } else {
                                column.setSequenceType(AutoIncrementType.SIMPLE);
                                return parseColumnRest(column);
                            }
                        }
                        return parseColumnRest(column);
                    } else if (lexer.identifierEquals(FnvHash.Constants.UNIT)) {
                        lexer.nextToken();

                        if (lexer.identifierEquals(FnvHash.Constants.COUNT)) {
                            lexer.nextToken();
                            SQLExpr unitCount = primary();
                            column.setUnitCount(unitCount);
                        }

                        if (lexer.token == Token.INDEX) {
                            lexer.nextToken();
                            SQLExpr unitIndex = primary();
                            column.setUnitIndex(unitIndex);
                        }

                        if (lexer.hashLCase() == FnvHash.Constants.STEP) {
                            lexer.nextToken();
                            SQLExpr unitIndex = primary();
                            column.setStep(unitIndex);
                        }
                    } else if (lexer.token == Token.LPAREN) {
                        column.setIdentity(parseIdentity());
                    }
                    return parseColumnRest(column);
                }
                break;
            case COMMENT:
                lexer.nextToken();

                if (lexer.token == Token.LITERAL_ALIAS) {
                    String alias = lexer.stringVal();
                    if (alias.length() > 2 && alias.charAt(0) == '"' && alias.charAt(alias.length() - 1) == '"') {
                        alias = alias.substring(1, alias.length() - 1);
                    }
                    column.setComment(alias);
                    lexer.nextToken();
                } else if (lexer.token == Token.LITERAL_CHARS) {
                    StringBuilder stringVal = new StringBuilder(lexer.stringVal());
                    lexer.nextToken();
                    parseColumnCommentLiteralCharsRest(stringVal);
                    column.setComment(stringVal.toString());
                } else {
                    column.setComment(primary());
                }
                return parseColumnRest(column);
            default:
                return parseColumnSpecific(column);
        }

        return column;
    }

    protected void parseIdentifySpecific() {
    }
    protected void parsePrimaryKeyRest(SQLPrimaryKeyImpl primaryKey){
    }

    protected void parseForeignKeyRest(SQLForeignKeyImpl foreignKey){
    }

    protected SQLColumnDefinition.Identity parseIdentity() {
        accept(Token.LPAREN);
        SQLColumnDefinition.Identity ident = new SQLColumnDefinition.Identity();
        parseIdentifySpecific();
        if (lexer.token == Token.LITERAL_INT) {
            ident.setSeed(lexer.integerValue().intValue());
            lexer.nextToken();
        } else {
            throw new ParserException("TODO : " + lexer.info());
        }

        if (lexer.token == Token.COMMA) {
            lexer.nextToken();
            if (lexer.token == Token.LITERAL_INT) {
                ident.setIncrement(lexer.integerValue().intValue());
                lexer.nextToken();
            } else {
                throw new ParserException("TODO : " + lexer.info());
            }
        }

        accept(Token.RPAREN);
        return ident;
    }

    protected SQLColumnReference parseReference() {
        SQLColumnReference fk = new SQLColumnReference();

        lexer.nextToken();
        fk.setTable(this.name());
        if (lexer.nextIf(LPAREN)) {
            this.names(fk.getColumns(), fk);
            accept(Token.RPAREN);
        }

        if (lexer.identifierEquals(FnvHash.Constants.MATCH)) {
            lexer.nextToken();
            if (lexer.identifierEquals("FULL") || lexer.token() == Token.FULL) {
                fk.setReferenceMatch(SQLForeignKeyImpl.Match.FULL);
                lexer.nextToken();
            } else if (lexer.identifierEquals(FnvHash.Constants.PARTIAL)) {
                fk.setReferenceMatch(SQLForeignKeyImpl.Match.PARTIAL);
                lexer.nextToken();
            } else if (lexer.identifierEquals(FnvHash.Constants.SIMPLE)) {
                fk.setReferenceMatch(SQLForeignKeyImpl.Match.SIMPLE);
                lexer.nextToken();
            } else {
                throw new ParserException("TODO : " + lexer.info());
            }
        }

        while (lexer.token() == Token.ON) {
            lexer.nextToken();

            if (lexer.token() == Token.DELETE) {
                lexer.nextToken();

                SQLForeignKeyImpl.Option option = parseReferenceOption();
                fk.setOnDelete(option);
            } else if (lexer.token() == Token.UPDATE) {
                lexer.nextToken();

                SQLForeignKeyImpl.Option option = parseReferenceOption();
                fk.setOnUpdate(option);
            } else {
                throw new ParserException("syntax error, expect DELETE or UPDATE, actual " + lexer.token() + " "
                        + lexer.info());
            }
        }

        return fk;
    }

    protected SQLForeignKeyImpl.Option parseReferenceOption() {
        SQLForeignKeyImpl.Option option;
        if (lexer.token() == Token.RESTRICT || lexer.identifierEquals(FnvHash.Constants.RESTRICT)) {
            option = SQLForeignKeyImpl.Option.RESTRICT;
            lexer.nextToken();
        } else if (lexer.identifierEquals(FnvHash.Constants.CASCADE) || lexer.token == CASCADE) {
            option = SQLForeignKeyImpl.Option.CASCADE;
            lexer.nextToken();
        } else if (lexer.token() == Token.SET) {
            lexer.nextToken();
            if (lexer.token() == Token.NULL) {
                accept(Token.NULL);
                option = SQLForeignKeyImpl.Option.SET_NULL;
            } else if (lexer.token == Token.DEFAULT) {
                accept(Token.DEFAULT);
                option = SQLForeignKeyImpl.Option.SET_DEFAULT;
            } else {
                throw new ParserException("syntax error," + lexer.info());
            }
        } else if (lexer.identifierEquals(FnvHash.Constants.NO)) {
            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.ACTION)) {
                option = SQLForeignKeyImpl.Option.NO_ACTION;
                lexer.nextToken();
            } else {
                throw new ParserException("syntax error, expect ACTION, actual " + lexer.token() + " "
                        + lexer.info());
            }
        } else {
            throw new ParserException("syntax error, expect ACTION, actual " + lexer.token() + " "
                    + lexer.info());
        }

        return option;
    }

    protected SQLColumnCheck parseColumnCheck() {
        lexer.nextToken();
        SQLExpr expr = this.primary();
        SQLColumnCheck check = new SQLColumnCheck(expr);

        if (lexer.token == Token.DISABLE) {
            lexer.nextToken();
            check.setEnable(false);
        } else if (lexer.token == Token.ENABLE) {
            lexer.nextToken();
            check.setEnable(true);
        } else if (lexer.identifierEquals(FnvHash.Constants.VALIDATE)) {
            lexer.nextToken();
            check.setValidate(Boolean.TRUE);
        } else if (lexer.identifierEquals(FnvHash.Constants.NOVALIDATE)) {
            lexer.nextToken();
            check.setValidate(Boolean.FALSE);
        } else if (lexer.identifierEquals(FnvHash.Constants.RELY)) {
            lexer.nextToken();
            check.setRely(Boolean.TRUE);
        } else if (lexer.identifierEquals(FnvHash.Constants.NORELY)) {
            lexer.nextToken();
            check.setRely(Boolean.FALSE);
        } else if (lexer.identifierEquals("ENFORCED")) {
            lexer.nextToken();
            check.setEnforced(true);
        } else if (lexer.token == Token.NOT) {
            Lexer.SavePoint mark = lexer.mark();
            lexer.nextToken();
            if (lexer.identifierEquals("ENFORCED")) {
                lexer.nextToken();
                check.setEnforced(false);
            } else {
                lexer.reset(mark);
            }
        }

        return check;
    }

    public SQLPrimaryKey parsePrimaryKey() {
        accept(Token.PRIMARY);
        accept(Token.KEY);

        SQLPrimaryKeyImpl pk = new SQLPrimaryKeyImpl();

        if (lexer.identifierEquals(FnvHash.Constants.CLUSTERED)) {
            lexer.nextToken();
            pk.setClustered(true);
        }

        accept(Token.LPAREN);
        orderBy(pk.getColumns(), pk);
        accept(Token.RPAREN);

        if (lexer.token == Token.DISABLE) {
            lexer.nextToken();
            acceptIdentifier("NOVALIDATE");
            pk.setDisableNovalidate(true);
        }
        parsePrimaryKeyRest(pk);
        return pk;
    }

    public SQLUnique parseUnique() {
        accept(Token.UNIQUE);

        SQLUnique unique = new SQLUnique();
        accept(Token.LPAREN);
        orderBy(unique.getColumns(), unique);
        accept(Token.RPAREN);

        if (lexer.token == Token.DISABLE) {
            lexer.nextToken();
            SavePoint savePoint = lexer.mark();
            if ("NOVALIDATE".equalsIgnoreCase(lexer.stringVal())) {
                unique.setDisableNovalidate(true);
                lexer.nextToken();
            } else {
                lexer.reset(savePoint);
            }
        } else if (lexer.token == Token.ENABLE) {
            lexer.nextToken();
            unique.setEnable(true);
        } else if (lexer.identifierEquals(FnvHash.Constants.VALIDATE)) {
            lexer.nextToken();
            unique.setValidate(Boolean.TRUE);
        } else if (lexer.identifierEquals(FnvHash.Constants.NOVALIDATE)) {
            lexer.nextToken();
            unique.setValidate(Boolean.FALSE);
        } else if (lexer.identifierEquals(FnvHash.Constants.RELY)) {
            lexer.nextToken();
            unique.setRely(Boolean.TRUE);
        } else if (lexer.identifierEquals(FnvHash.Constants.NORELY)) {
            lexer.nextToken();
            unique.setRely(Boolean.FALSE);
        }

        return unique;
    }

    public void parseAssignItem(List<SQLAssignItem> outList, SQLObject parent) {
        accept(Token.LPAREN);
        if (lexer.token() != Token.RPAREN) {
            for (; ; ) {
                SQLAssignItem item = this.parseAssignItem(true, parent);
                item.setParent(parent);
                outList.add(item);
                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }
                break;
            }
        }
        accept(Token.RPAREN);
    }

    public SQLAssignItem parseAssignItem() {
        return parseAssignItem(true, null);
    }

    public SQLAssignItem parseAssignItem(boolean variant) {
        return parseAssignItem(variant, null);
    }

    protected void parseAssignItemDot() {
    }

    protected void parseAssignItemNcToBeExecuted() {
    }

    protected boolean parseAssignItemTblProperties(SQLAssignItem item) {
        return false;
    }

    protected SQLExpr parseAssignItemSQLPropertyExprAndSub(SQLExpr sqlExpr) {
        return sqlExpr;
    }

    protected void parseAssignItemSQLIdentifierExpr(SQLExpr sqlExpr) {
    }

    protected SQLExpr parseAssignItemSQLPropertyExpr(SQLExpr sqlExpr) {
        return sqlExpr;
    }

    protected void parseAssignItemSQLIdentifierExprAndVariant(SQLIdentifierExpr ident) {
    }

    protected boolean parseAssignItemSQLMethodInvokeExpr(SQLExpr sqlExpr, SQLAssignItem item) {
        return false;
    }

    protected void parseAssignItemEq(SQLObject parent) {
        lexer.nextToken();
    }

    protected SQLExpr parseAssignItemOnLiteralFloat(SQLExpr sqlExpr) {
        return sqlExpr;
    }

    protected SQLExpr parseAssignItemOnColon(SQLExpr sqlExpr) {
        return sqlExpr;
    }

    protected void parseAssignItemOnComma(SQLExpr sqlExpr, SQLAssignItem item, SQLObject parent) {
        item.setValue(sqlExpr);
    }
    public SQLAssignItem parseAssignItem(boolean variant, SQLObject parent) {
        SQLAssignItem item = new SQLAssignItem();
        parseAssignItemDot();

        SQLExpr var;

        parseAssignItemNcToBeExecuted();

        if (lexer.identifierEquals(FnvHash.Constants.TBLPROPERTIES)) {
            var = new SQLIdentifierExpr(lexer.stringVal());
            lexer.nextToken();
            if (parseAssignItemTblProperties(item)) {
                return item;
            }
        } else {
            var = primary();
        }

        var = parseAssignItemSQLPropertyExprAndSub(var);
        parseAssignItemSQLIdentifierExpr(var);
        var = parseAssignItemSQLPropertyExpr(var);

        if (variant && var instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr ident = (SQLIdentifierExpr) var;
            parseAssignItemSQLIdentifierExprAndVariant(ident);
            String identName = (ident).getName();
            if (identName.indexOf('@') != -1
                    || identName.indexOf('#') != -1
                    || identName.indexOf(':') != -1
                    || identName.indexOf(' ') != -1) {
                var = new SQLVariantRefExpr(identName);
            }
        }

        if (parseAssignItemSQLMethodInvokeExpr(var, item)) {
            return item;
        }

        item.setTarget(var);

        if (lexer.token == Token.COLONEQ) {
            lexer.nextToken();
        } else if (lexer.token == Token.TRUE || lexer.identifierEquals(FnvHash.Constants.TRUE)) {
            lexer.nextToken();
            item.setValue(new SQLBooleanExpr(true));
            return item;
        } else if (lexer.token == Token.ON) {
            lexer.nextToken();
            item.setValue(new SQLIdentifierExpr("ON"));
            return item;
        } else if ((lexer.token == Token.RPAREN || lexer.token == Token.COMMA || lexer.token == Token.SET)
                && dialectFeatureEnabled(ParseAssignItemRparenCommaSetReturn)) {
            return item;
        } else {
            if (lexer.token == Token.EQ) {
                parseAssignItemEq(parent);
                if (lexer.token == Token.SEMI && dialectFeatureEnabled(ParseAssignItemEqSemiReturn)) {
                    return item;
                }
            } else if (dialectFeatureEnabled(ParseAssignItemSkip)
                    || lexer.token == Token.QUES
                    || lexer.token == Token.LITERAL_CHARS
                    || lexer.token == Token.LITERAL_ALIAS
                    || lexer.identifierEquals("utf8mb4")
            ) {
                // skip
            } else if (lexer.token == Token.EQEQ && dialectFeatureEnabled(ParseAssignItemEqeq)) {
                lexer.nextToken();
            } else {
                accept(Token.EQ);
            }
        }

        if (lexer.token == Token.ON) {
            item.setValue(new SQLIdentifierExpr(lexer.stringVal()));
            lexer.nextToken();
        } else {
            if (lexer.token == Token.ALL) {
                item.setValue(new SQLIdentifierExpr(lexer.stringVal()));
                lexer.nextToken();
            } else {
                SQLExpr expr = expr();
                expr = parseAssignItemOnLiteralFloat(expr);
                expr = parseAssignItemOnColon(expr);
                parseAssignItemOnComma(expr, item, parent);
            }
        }
        return item;
    }

    public List<SQLCommentHint> parseHints() {
        List<SQLCommentHint> hints = new ArrayList<SQLCommentHint>();
        parseHints(hints);
        return hints;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void parseHints(List hints) {
        while (lexer.token == Token.HINT) {
            String text = lexer.stringVal();
            SQLCommentHint hint;

            if (lexer.isEnabled(SQLParserFeature.TDDLHint)
                    && (text.startsWith("+ TDDL")
                    || text.startsWith("+TDDL")
                    || text.startsWith("!TDDL")
                    || text.startsWith("TDDL"))) {
                hint = new TDDLHint(text);
            } else {
                hint = new SQLCommentHint(text);
            }

            if (lexer.commentCount > 0) {
                hint.addBeforeComment(lexer.comments);
            }

            hints.add(hint);
            lexer.nextToken();
        }
    }

    public SQLCommentHint parseHint() {
        String text = lexer.stringVal();
        SQLCommentHint hint;

        if (lexer.isEnabled(SQLParserFeature.TDDLHint)
                && (text.startsWith("+ TDDL")
                || text.startsWith("+TDDL")
                || text.startsWith("!TDDL")
                || text.startsWith("TDDL"))) {
            hint = new TDDLHint(text);
        } else {
            hint = new SQLCommentHint(text);
        }

        if (lexer.commentCount > 0) {
            hint.addBeforeComment(lexer.comments);
        }

        lexer.nextToken();
        return hint;
    }

    protected void parseIndexSpecific(SQLIndexDefinition indexDefinition) {
        indexDefinition.setName(name());
    }

    protected void parseIndexOptions(SQLIndexDefinition indexDefinition) {
        while (true) {
            if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
                indexDefinition.getOptions().setComment(primary());
            } else {
                break;
            }
        }
    }

    public void parseIndex(SQLIndexDefinition indexDefinition) {
        if (lexer.token() == Token.CONSTRAINT) {
            indexDefinition.setHasConstraint(true);
            lexer.nextToken();
            if (lexer.token() == Token.IDENTIFIER
                    && !lexer.identifierEquals(FnvHash.Constants.GLOBAL)
                    && !lexer.identifierEquals(FnvHash.Constants.LOCAL)
                    && !lexer.identifierEquals(FnvHash.Constants.SPATIAL)) {
                indexDefinition.setSymbol(name());
            }
        }

        if (lexer.identifierEquals(FnvHash.Constants.GLOBAL)) {
            indexDefinition.setGlobal(true);
            lexer.nextToken();
        } else if (lexer.identifierEquals(FnvHash.Constants.LOCAL)) {
            indexDefinition.setLocal(true);
            lexer.nextToken();
        }

        if (lexer.token() == Token.FULLTEXT
                || lexer.token() == Token.UNIQUE
                || lexer.token() == Token.PRIMARY
                || lexer.identifierEquals(FnvHash.Constants.SPATIAL)
                || lexer.identifierEquals(FnvHash.Constants.CLUSTERED)
                || lexer.identifierEquals(FnvHash.Constants.CLUSTERING)
                || lexer.identifierEquals(FnvHash.Constants.ANN)) {
            indexDefinition.setType(lexer.stringVal());
            lexer.nextToken();
        }

        if (lexer.identifierEquals(FnvHash.Constants.GLOBAL)) {
            indexDefinition.setGlobal(true);
            lexer.nextToken();
        } else if (lexer.identifierEquals(FnvHash.Constants.LOCAL)) {
            indexDefinition.setLocal(true);
            lexer.nextToken();
        }

        if (lexer.token() == Token.INDEX) {
            indexDefinition.setIndex(true);
            lexer.nextToken();
        } else if (lexer.token() == Token.KEY) {
            indexDefinition.setKey(true);
            lexer.nextToken();
        }

        while (lexer.token() != Token.LPAREN && lexer.token() != Token.ON) {
            parseIndexSpecific(indexDefinition);
        }

        if (lexer.token() == Token.ON) {
            lexer.nextToken();
            indexDefinition.setTable(new SQLExprTableSource(name()));
        }

        parseIndexRest(indexDefinition, indexDefinition.getParent());

        // Options, partitions.
        parseIndexOptions(indexDefinition);
    }

    public SQLConstraint parseConstraint() {
        SQLName name = null;

        boolean nextIfConstraint = lexer.nextIf(CONSTRAINT);
        if (nextIfConstraint) {
            if (lexer.token != PRIMARY
                    && lexer.token != UNIQUE
                    && lexer.token != KEY
                    && lexer.token != FOREIGN
                    && lexer.token != CHECK
                    && lexer.token != DEFAULT
            ) {
                name = this.name();
            }
        }

        SQLConstraint constraint;
        switch (lexer.token) {
            case PRIMARY:
                constraint = parsePrimaryKey();
                break;
            case UNIQUE:
                constraint = parseUnique();
                break;
            case KEY:
                constraint = parseUnique();
                break;
            case FOREIGN:
                constraint = parseForeignKey();
                break;
            case CHECK:
                constraint = parseCheck();
                break;
            case DEFAULT:
                constraint = parseDefault();
                break;
            default:
                throw new ParserException("TODO : " + lexer.info());
        }

        if (name == null && lexer.token == Token.IDENTIFIER) {
            name = name();
        }

        if (name != null) {
            constraint.setName(name);
        }

        if (nextIfConstraint) {
            constraint.setHasConstraint(true);
        }

        return constraint;
    }

    public SQLCheck parseCheck() {
        accept(Token.CHECK);
        SQLCheck check = createCheck();
        accept(Token.LPAREN);
        check.setExpr(this.expr());
        if (check.getExpr() instanceof SQLExprImpl) {
            ((SQLExprImpl) check.getExpr()).setParenthesized(true);
        }
        accept(Token.RPAREN);
        return check;
    }

    public SQLDefault parseDefault() {
        accept(Token.DEFAULT);
        SQLDefault sqlDefault = new SQLDefault();

        if (lexer.token == Token.LPAREN) {
            while (lexer.token == Token.LPAREN) {
                accept(Token.LPAREN);
            }

            sqlDefault.setExpr(this.expr());

            while (lexer.token == Token.RPAREN) {
                accept(Token.RPAREN);
            }
        } else {
            sqlDefault.setExpr(this.expr());
        }

        accept(Token.FOR);
        sqlDefault.setColumn(this.expr());

        if (lexer.token == Token.WITH) {
            lexer.nextToken();
            accept(Token.VALUES);
            sqlDefault.setWithValues(true);
        }

        return sqlDefault;
    }

    protected SQLCheck createCheck() {
        return new SQLCheck();
    }

    public SQLForeignKeyConstraint parseForeignKey() {
        accept(Token.FOREIGN);
        accept(Token.KEY);

        SQLForeignKeyImpl fk = createForeignKey();

        accept(Token.LPAREN);
        this.names(fk.getReferencingColumns(), fk);
        accept(Token.RPAREN);

        accept(Token.REFERENCES);

        fk.setReferencedTableName(this.name());

        if (lexer.token == Token.LPAREN) {
            lexer.nextToken();
            this.names(fk.getReferencedColumns(), fk);
            accept(Token.RPAREN);
        }

        if (lexer.token == Token.ON) {
            lexer.nextToken();
            accept(Token.DELETE);
            if (lexer.identifierEquals(FnvHash.Constants.CASCADE) || lexer.token == Token.CASCADE) {
                lexer.nextToken();
                fk.setOnDeleteCascade(true);
            } else {
                accept(Token.SET);
                accept(Token.NULL);
                fk.setOnDeleteSetNull(true);
            }
        }

        if (lexer.token == Token.DISABLE) {
            Lexer.SavePoint mark = lexer.mark();

            lexer.nextToken();
            if (lexer.identifierEquals(FnvHash.Constants.NOVALIDATE)) {
                lexer.nextToken();
                fk.setDisableNovalidate(true);
            } else {
                lexer.reset(mark);
            }
        }
        parseForeignKeyRest(fk);
        return fk;
    }

    protected SQLForeignKeyImpl createForeignKey() {
        return new SQLForeignKeyImpl();
    }

    protected SQLExpr parseSelectItemRest(String ident, long hash_lower) {
        SQLExpr expr = null;
        if (FnvHash.Constants.TIMESTAMP == hash_lower
                && lexer.stringVal().charAt(0) != '`'
                && lexer.token == Token.LITERAL_CHARS) {
            String literal = lexer.stringVal();
            lexer.nextToken();

            SQLTimestampExpr ts = new SQLTimestampExpr(literal);
            expr = ts;

            if (lexer.identifierEquals(FnvHash.Constants.AT)) {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();

                String timeZone = null;
                if (lexer.identifierEquals(FnvHash.Constants.TIME)) {
                    lexer.nextToken();
                    if (lexer.identifierEquals(FnvHash.Constants.ZONE)) {
                        lexer.nextToken();
                        timeZone = lexer.stringVal();
                        lexer.nextToken();
                    }
                }
                if (timeZone == null) {
                    lexer.reset(mark);
                } else {
                    ts.setTimeZone(timeZone);
                }
            }
        } else if (FnvHash.Constants.DATETIME == hash_lower
                && lexer.stringVal().charAt(0) != '`'
                && lexer.token == Token.LITERAL_CHARS) {
            String literal = lexer.stringVal();
            lexer.nextToken();

            SQLDateTimeExpr ts = new SQLDateTimeExpr(literal);
            expr = ts;
        }
        return expr;
    }

    protected Pair<String, SQLExpr> parseSelectItemIdentifier(SQLExpr expr) {
        String as = lexer.stringVal();
        if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && as.length() > 1) {
            as = StringUtils.removeNameQuotes(as);
        }
        lexer.nextTokenComma();
        return Pair.of(as, expr);
    }

    protected SQLExpr parseSelectItemMethod(SQLExpr expr) {
        return this.methodRest(expr, true);
    }

    protected String parseSelectItemAlias(String alias) {
        boolean specialChar = false;
        for (int i = 0; i < alias.length(); ++i) {
            char ch = alias.charAt(i);
            if (!CharTypes.isIdentifierChar(ch)) {
                specialChar = true;
                break;
            }
        }
        if (specialChar) {
            alias = alias.replaceAll("\"", "\\\"");
        }
        return alias;
    }
    public SQLSelectItem parseSelectItem() {
        SQLExpr expr;
        boolean connectByRoot = false;
        Token token = lexer.token;
        int startPos = lexer.startPos;
        if (token == Token.IDENTIFIER
                && !(lexer.hashLCase() == -5808529385363204345L && lexer.charAt(lexer.pos) == '\'' && (dialectFeatureEnabled(
                ParseSelectItemPrefixX))) // x'123' X'123'
        ) {
            int sourceLine = -1, sourceColumn = -1;
            if (lexer.isKeepSourceLocation()) {
                lexer.computeRowAndColumn();
                sourceLine = lexer.getPosLine();
                sourceColumn = lexer.getPosColumn();
            }

            String ident = lexer.stringVal();
            long hash_lower = lexer.hashLCase();
            lexer.nextTokenComma();

            if (hash_lower == FnvHash.Constants.CONNECT_BY_ROOT) {
                connectByRoot = lexer.token != Token.LPAREN;
                if (connectByRoot) {
                    expr = new SQLIdentifierExpr(lexer.stringVal());
                    lexer.nextToken();
                } else {
                    expr = new SQLIdentifierExpr(ident);
                }
            } else if (FnvHash.Constants.DATE == hash_lower
                    && lexer.stringVal().charAt(0) != '`'
                    && (lexer.token == Token.LITERAL_CHARS || lexer.token == LITERAL_ALIAS)
                    && (dialectFeatureEnabled(SQLDateExpr))
            ) {
                String literal = lexer.stringVal();
                lexer.nextToken();

                SQLDateExpr dateExpr = new SQLDateExpr();
                dateExpr.setValue(literal);

                expr = dateExpr;
            } else if (FnvHash.Constants.TIME == hash_lower
                    && lexer.token == Token.LITERAL_CHARS) {
                String literal = lexer.stringVal();
                lexer.nextToken();

                expr = new SQLTimeExpr(literal);
            } else if (hash_lower == FnvHash.Constants.DECIMAL
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLDecimalExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.REAL
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLRealExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.DOUBLE
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLDoubleExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.FLOAT
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLFloatExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.BIGINT
                    && lexer.token == Token.LITERAL_CHARS) {
                String strVal = lexer.stringVal();
                if (strVal.startsWith("--")) {
                    strVal = strVal.substring(2);
                }
                expr = new SQLBigIntExpr(strVal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.INTEGER
                    && lexer.token == Token.LITERAL_CHARS) {
                String strVal = lexer.stringVal();
                if (strVal.startsWith("--")) {
                    strVal = strVal.substring(2);
                }
                SQLIntegerExpr integerExpr = SQLIntegerExpr.ofIntOrLong(Long.parseLong(strVal));
                integerExpr.setType("INTEGER");
                expr = integerExpr;
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.SMALLINT
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLSmallIntExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.TINYINT
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLTinyIntExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.JSON
                    && lexer.token == Token.LITERAL_CHARS) {
                String decimal = lexer.stringVal();
                expr = new SQLJSONExpr(decimal);
                lexer.nextToken();
            } else if (hash_lower == FnvHash.Constants.TRY_CAST) {
                accept(Token.LPAREN);
                SQLCastExpr cast = new SQLCastExpr();
                cast.setTry(true);
                cast.setExpr(expr());
                accept(Token.AS);
                cast.setDataType(parseDataType(false));
                accept(Token.RPAREN);
                expr = cast;
            } else if (FnvHash.Constants.CURRENT_USER == hash_lower
                    && ident.charAt(0) != '`'
                    && lexer.token != Token.LPAREN && isEnabled(SQLParserFeature.EnableCurrentUserExpr)) {
                expr = new SQLCurrentUserExpr();
            } else {
                expr = parseSelectItemRest(ident, hash_lower);
                if (expr == null) {
                    if (lexer.isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
                        ident = SQLUtils.normalize(ident, dbType);
                    }
                    if (ident.charAt(0) == '"' && ident.charAt(ident.length() - 1) == '"') {
                        hash_lower = FnvHash.hashCode64(ident);
                    }
                    SQLIdentifierExpr identifierExpr = new SQLIdentifierExpr(ident, hash_lower);
                    if (lexer.keepSourceLocation) {
                        lexer.computeRowAndColumn();
                        identifierExpr.setSource(lexer.posLine, lexer.posColumn);
                    }
                    expr = identifierExpr;
                }
            }

            token = lexer.token;

            if (token == Token.DOT) {
                lexer.nextTokenIdent();

                if (lexer.token == Token.STAR) {
                    expr = dotRest(expr);
                } else {
                    String name = lexer.stringVal();
                    long name_hash_lower = lexer.hashLCase();
                    lexer.nextTokenComma();

                    token = lexer.token;
                    if (token == Token.LPAREN) {
                        boolean aggregate = hash_lower == FnvHash.Constants.WMSYS && name_hash_lower == FnvHash.Constants.WM_CONCAT;
                        expr = methodRest(expr, name, aggregate);
                        token = lexer.token;
                    } else {
                        if (name_hash_lower == FnvHash.Constants.NEXTVAL) {
                            expr = new SQLSequenceExpr((SQLIdentifierExpr) expr, SQLSequenceExpr.Function.NextVal);
                        } else if (name_hash_lower == FnvHash.Constants.CURRVAL) {
                            expr = new SQLSequenceExpr((SQLIdentifierExpr) expr, SQLSequenceExpr.Function.CurrVal);
                        } else if (name_hash_lower == FnvHash.Constants.PREVVAL) {
                            expr = new SQLSequenceExpr((SQLIdentifierExpr) expr, SQLSequenceExpr.Function.PrevVal);
                        } else {
                            if (lexer.isEnabled(SQLParserFeature.IgnoreNameQuotes)) {
                                name = SQLUtils.normalize(name, dbType);
                            }
                            if (name.charAt(0) == '"') {
                                name_hash_lower = FnvHash.hashCode64(name);
                            }
                            expr = new SQLPropertyExpr(expr, name, name_hash_lower);
                        }
                    }
                }
            }

            if (token == Token.COMMA) {
                return new SQLSelectItem(expr, (String) null, connectByRoot);
            }

            if (token == Token.AS) {
                lexer.nextTokenAlias();
                String as = null;
                if (lexer.token != Token.COMMA && lexer.token != Token.FROM) {
                    as = lexer.stringVal();
                    if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && as.length() > 1) {
                        as = StringUtils.removeNameQuotes(as);
                    }

                    lexer.nextTokenComma();

                    if (lexer.token == Token.DOT) {
                        lexer.nextToken();
                        as += '.' + lexer.stringVal();
                        lexer.nextToken();
                    }
                }

                return new SQLSelectItem(expr, as, connectByRoot);
            }

            if (token == Token.LITERAL_ALIAS) {
                String as = lexer.stringVal();
                if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && as.length() > 1) {
                    as = StringUtils.removeNameQuotes(as);
                }
                lexer.nextTokenComma();
                return new SQLSelectItem(expr, as, connectByRoot);
            }

            if (token == Token.IDENTIFIER
                    && hash_lower != FnvHash.Constants.CURRENT) {
                Pair<String, SQLExpr> pair = parseSelectItemIdentifier(expr);
                String as = pair.getKey();
                expr = pair.getValue();
                return new SQLSelectItem(expr, as, connectByRoot);
            }

            if (token == Token.LPAREN) {
                expr = parseSelectItemMethod(expr);
            } else {
                expr = this.primaryRest(expr);
            }
            expr = this.exprRest(expr);

            if (sourceLine != -1) {
                expr.setSource(sourceLine, sourceColumn);
            }
        } else if (lexer.nextIf(STAR)) {
            SQLAllColumnExpr star = new SQLAllColumnExpr();

            if (lexer.nextIf(Token.EXCEPT)) {
                accept(Token.LPAREN);
                List<SQLExpr> except = new ArrayList<>();
                this.exprList(except, star);
                star.setExcept(except);
                accept(Token.RPAREN);
            }

            if (lexer.nextIf(REPLACE)) {
                accept(Token.LPAREN);
                this.aliasedItems(star.getReplace(), star);
                accept(Token.RPAREN);
            }

            expr = star;
            return new SQLSelectItem(expr, (String) null, connectByRoot);
        } else if (token == Token.DO || token == Token.JOIN || token == Token.TABLESPACE) {
            expr = this.name();
            expr = this.exprRest(expr);
        } else {
            if (lexer.token == Token.DISTINCT && dbType == DbType.elastic_search) {
                lexer.nextToken();
            }

            while (lexer.token == Token.HINT) {
                lexer.nextToken();
            }
            expr = expr();
        }

        String alias;
        List<String> aliasList = null;
        if (expr instanceof SQLVariantRefExpr && ((SQLVariantRefExpr) expr).isTemplateParameter() && lexer.token != AS) {
            alias = null;
        } else {
            switch (lexer.token) {
                case FULL:
                case TABLESPACE:
                    alias = lexer.stringVal();
                    lexer.nextToken();
                    break;
                case AS:
                    lexer.nextTokenAlias();
                    if (lexer.token == Token.LITERAL_INT) {
                        alias = '"' + lexer.stringVal() + '"';
                        lexer.nextToken();
                    } else if (lexer.token == Token.LPAREN) {
                        lexer.nextToken();
                        aliasList = new ArrayList<String>();
                        for (; ; ) {
                            String stringVal = lexer.stringVal();
                            lexer.nextToken();
                            aliasList.add(stringVal);
                            if (lexer.token() == Token.COMMA) {
                                lexer.nextToken();
                                continue;
                            }
                            break;
                        }
                        accept(Token.RPAREN);

                        alias = null;
                    } else {
                        alias = alias();
                    }
                    break;
                case EOF:
                    alias = null;
                    break;
                default:
                    alias = as();
                    break;
            }
        }

        if (alias == null && isEnabled(SQLParserFeature.SelectItemGenerateAlias)
                && (!(expr instanceof SQLName))
                && !(expr instanceof SQLNumericLiteralExpr)
                && !(expr instanceof SQLCharExpr)
                && !(expr instanceof SQLNullExpr)
                && !(expr instanceof SQLBooleanExpr)) {
            alias = lexer.text.substring(startPos, lexer.startPos);

            if (lexer.comments != null) {
                for (int i = lexer.comments.size() - 1; i >= 0; i--) {
                    String comment = lexer.comments.get(i);
                    int p = alias.lastIndexOf(comment);
                    if (p >= 0) {
                        alias = alias.substring(0, p - 1);
                    }
                }
            }

            alias = CharTypes.trim(alias);

            if (alias.length() > 0) {
                alias = parseSelectItemAlias(alias);
            }
        }

        SQLSelectItem selectItem;
        if (aliasList != null) {
            selectItem = new SQLSelectItem(expr, aliasList, connectByRoot);
        } else {
            selectItem = new SQLSelectItem(expr, alias, connectByRoot);
        }
        if (lexer.token == Token.HINT && !lexer.isEnabled(SQLParserFeature.StrictForWall)) {
            String comment = "/*" + lexer.stringVal() + "*/";
            selectItem.addAfterComment(comment);
            lexer.nextToken();
        }

        return selectItem;
    }

    public SQLPartition parsePartition() {
        if (lexer.nextIf(Token.PARTITION)) {
            SQLPartitionSingle partitionDef = new SQLPartitionSingle();
            SQLName name = this.name();
            partitionDef.setName(name);
            partitionDef.setValues(this.parsePartitionValues());
            return partitionDef;
        } else if (lexer.nextIf(START) || lexer.nextIfIdentifier(FnvHash.Constants.START)) {
            SQLPartitionBatch partitionDef = new SQLPartitionBatch();
            accept(Token.LPAREN);
            partitionDef.setStart(this.expr());
            accept(Token.RPAREN);
            if (lexer.nextIf(Token.END) || lexer.nextIfIdentifier(FnvHash.Constants.END)) {
                accept(Token.LPAREN);
                partitionDef.setEnd(this.expr());
                accept(Token.RPAREN);
            } else {
                throw new ParserException("syntax error, expect END, " + lexer.info());
            }
            acceptIdentifier(FnvHash.Constants.EVERY);
            accept(Token.LPAREN);
            partitionDef.setEvery(this.expr());
            accept(Token.RPAREN);
            return partitionDef;
        }
        return null;
    }

    public SQLPartitionSpec parsePartitionSpec() {
        SQLPartitionSpec spec = new SQLPartitionSpec();
        accept(Token.PARTITION);
        accept(Token.LPAREN);

        for (; ; ) {
            SQLPartitionSpec.Item item = new SQLPartitionSpec.Item();
            item.setColumn(
                    this.name());

            accept(Token.EQ);

            item.setValue(
                    this.expr());

            spec.addItem(item);

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            } else {
                break;
            }
        }

        accept(Token.RPAREN);
        return spec;
    }

    public SQLPartitionBy parsePartitionBy() {
        lexer.nextToken();
        accept(Token.BY);

        SQLPartitionBy partitionClause = null;
        if (lexer.identifierEquals("VALUE")) {
            partitionClause = new SQLPartitionByValue();
            if (lexer.identifierEquals(FnvHash.Constants.VALUE)) {
                lexer.nextToken();

                if (lexer.token() == Token.LPAREN) {
                    lexer.nextToken();
                    partitionClause.addColumn(expr());
                    accept(Token.RPAREN);
                }
            }

            if (lexer.identifierEquals(FnvHash.Constants.LIFECYCLE)) {
                lexer.nextToken();
                partitionClause.setLifeCycle((SQLIntegerExpr) expr());
            }
        }
        return partitionClause;
    }

    public SQLExpr parseGroupingSet() {
        String tmp = lexer.stringVal();
        acceptIdentifier("GROUPING");

        SQLGroupingSetExpr expr = new SQLGroupingSetExpr();

        if (lexer.token == Token.SET || lexer.identifierEquals(FnvHash.Constants.SET)) {
            lexer.nextToken();
        } else {
            return new SQLIdentifierExpr(tmp);
        }

        accept(Token.LPAREN);

        this.exprList(expr.getParameters(), expr);

        accept(Token.RPAREN);

        return expr;
    }

    public SQLPartitionValue parsePartitionValues() {
        SQLPartitionValue values = null;
        if (lexer.nextIf(Token.VALUES)) {
            if (lexer.nextIf(Token.IN)) {
                values = new SQLPartitionValue(SQLPartitionValue.Operator.In);
                accept(Token.LPAREN);
                this.exprList(values.getItems(), values);
                accept(Token.RPAREN);
            } else if (lexer.nextIfIdentifier(FnvHash.Constants.LESS)) {
                acceptIdentifier(FnvHash.Constants.THAN);
                values = new SQLPartitionValue(SQLPartitionValue.Operator.LessThan);
                if (lexer.nextIfIdentifier(FnvHash.Constants.MAXVALUE)) {
                    SQLIdentifierExpr maxValue = new SQLIdentifierExpr(lexer.stringVal());
                    maxValue.setParent(values);
                    values.addItem(maxValue);
                } else {
                    accept(Token.LPAREN);
                    this.exprList(values.getItems(), values);
                    accept(Token.RPAREN);
                }
            } else if (lexer.nextIf(Token.LPAREN)) {
                values = new SQLPartitionValue(SQLPartitionValue.Operator.List);
                this.exprList(values.getItems(), values);
                accept(Token.RPAREN);
            } else if (lexer.nextIf(Token.LBRACKET)) {
                values = new SQLPartitionValue(SQLPartitionValue.Operator.FixedRange);
                this.exprList(values.getItems(), values);
                accept(Token.RPAREN);
            }
        }
        return values;
    }

    protected static boolean isIdent(SQLExpr expr, String name) {
        if (expr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr identExpr = (SQLIdentifierExpr) expr;
            return identExpr.getName().equalsIgnoreCase(name);
        }
        return false;
    }

    public SQLLimit parseLimit() {
        if (lexer.token != Token.LIMIT) {
            return null;
        }

        SQLLimit limit = new SQLLimit();
        lexer.nextTokenValue();

        SQLExpr temp;
        if (lexer.token == Token.LITERAL_INT) {
            temp = new SQLIntegerExpr(lexer.integerValue());
            lexer.nextTokenComma();
            if (lexer.token != Token.COMMA && lexer.token != Token.EOF && lexer.token != Token.IDENTIFIER) {
                temp = this.primaryRest(temp);
                temp = this.exprRest(temp);
            }
        } else {
            temp = this.expr();
        }

        if (lexer.token == (Token.COMMA)) {
            limit.setOffset(temp);
            lexer.nextTokenValue();

            SQLExpr rowCount;
            if (lexer.token == Token.LITERAL_INT) {
                rowCount = new SQLIntegerExpr(lexer.integerValue());
                lexer.nextToken();
                if (lexer.token != Token.EOF && lexer.token != Token.IDENTIFIER) {
                    rowCount = this.primaryRest(rowCount);
                    rowCount = this.exprRest(rowCount);
                }
            } else {
                rowCount = this.expr();
            }

            limit.setRowCount(rowCount);
        } else if (lexer.identifierEquals(FnvHash.Constants.OFFSET)) {
            limit.setRowCount(temp);
            lexer.nextToken();
            limit.setOffset(this.expr());
            limit.setOffsetClause(true);
        } else {
            limit.setRowCount(temp);
        }

        if (lexer.token == Token.BY && dialectFeatureEnabled(ParseLimitBy)) {
            lexer.nextToken();

            for (; ; ) {
                SQLExpr item = this.expr();
                limit.addBy(item);
                if (lexer.token == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }
                break;
            }
        }

        return limit;
    }

    public void parseIndexRest(SQLIndex idx) {
        parseIndexRest(idx, idx);
    }

    public void parseIndexRest(SQLIndex idx, SQLObject parent) {
        accept(Token.LPAREN);
        for (; ; ) {
            SQLSelectOrderByItem selectOrderByItem = this.parseSelectOrderByItem();
            selectOrderByItem.setParent(parent);
            idx.getColumns().add(selectOrderByItem);
            if (!(lexer.token() == (Token.COMMA))) {
                break;
            } else {
                lexer.nextToken();
            }
        }
        accept(Token.RPAREN);

        if (lexer.identifierEquals(FnvHash.Constants.COVERING)) {
            Lexer.SavePoint mark = lexer.mark();

            lexer.nextToken();

            if (lexer.token == Token.LPAREN) {
                lexer.nextToken();
            } else {
                lexer.reset(mark);
                return;
            }

            for (; ; ) {
                SQLName name = this.name();
                name.setParent(parent);
                idx.getCovering().add(name);
                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();
                } else {
                    break;
                }
            }

            accept(Token.RPAREN);
        }
    }

    public SQLExternalRecordFormat parseRowFormat() {
        lexer.nextToken();
        acceptIdentifier("FORMAT");

        if (lexer.identifierEquals(FnvHash.Constants.DELIMITED)) {
            lexer.nextToken();
        }

        SQLExternalRecordFormat format = new SQLExternalRecordFormat();

        Lexer.SavePoint mark = lexer.mark();
        String strVal = lexer.stringVal();
        if (NULL.equals(lexer.token())) {
            lexer.nextToken();
            acceptIdentifier("DEFINED");
            accept(Token.AS);
            strVal = lexer.stringVal();
            String value = strVal.substring(1, strVal.length() - 1);
            SQLCharExpr emptyExpr = new SQLCharExpr(value);
            format.setNullDefinedAs(emptyExpr);
            lexer.nextToken();
        }
//        for (; ; ) {
//            if (strVal.equalsIgnoreCase("FULL")) {
//
//                stmt.setTruncate(true);
//                lexer.nextToken();
//                mark = lexer.mark();
//                strVal = lexer.stringVal();
//                continue;
//            } else {
//                lexer.reset(mark);
//                break;
//            }
//        }

        if (lexer.identifierEquals(FnvHash.Constants.LINES)) {
            lexer.nextToken();
            acceptIdentifier("TERMINATED");
            accept(Token.BY);

            format.setLinesTerminatedBy(this.expr());
        }
        if (lexer.identifierEquals(FnvHash.Constants.FIELDS)) {
            lexer.nextToken();
            acceptIdentifier("TERMINATED");
            accept(Token.BY);

            format.setTerminatedBy(this.expr());
        } else if (lexer.identifierEquals("FIELD")) {
            throw new ParserException("syntax error, expect FIELDS, " + lexer.info());
        }

        if (lexer.token() == Token.ESCAPE || lexer.identifierEquals(FnvHash.Constants.ESCAPED)) {
            lexer.nextToken();
            accept(Token.BY);
            format.setEscapedBy(this.expr());
        }

        if (lexer.identifierEquals(FnvHash.Constants.LINES)) {
            lexer.nextToken();
            acceptIdentifier("TERMINATED");
            accept(Token.BY);

            format.setLinesTerminatedBy(this.expr());
        }

        if (lexer.identifierEquals(FnvHash.Constants.COLLECTION)) {
            lexer.nextToken();
            acceptIdentifier("ITEMS");
            acceptIdentifier("TERMINATED");
            accept(Token.BY);
            format.setCollectionItemsTerminatedBy(this.expr());
        }

        if (lexer.identifierEquals(FnvHash.Constants.MAP)) {
            lexer.nextToken();
            acceptIdentifier("KEYS");
            acceptIdentifier("TERMINATED");
            accept(Token.BY);
            format.setMapKeysTerminatedBy(this.expr());
        }

        if (lexer.identifierEquals(FnvHash.Constants.SERDE)) {
            lexer.nextToken();
            format.setSerde(this.expr());
        }

        return format;
    }

    public SQLTop parseTop() {
        if (lexer.token() == Token.TOP) {
            SQLTop top = new SQLTop();
            lexer.computeRowAndColumn(top);
            lexer.nextToken();

            boolean paren = false;
            if (lexer.token() == Token.LPAREN) {
                top.setParentheses(true);
                paren = true;
                lexer.nextToken();
            }

            if (lexer.token() == Token.LITERAL_INT) {
                top.setExpr(lexer.integerValue().intValue());
                lexer.nextToken();
            } else {
                top.setExpr(primary());
            }

            if (paren) {
                accept(Token.RPAREN);
            }

            if (lexer.token() == Token.PERCENT) {
                lexer.nextToken();
                top.setPercent(true);
            }

            return top;
        }

        return null;
    }

    protected SQLStructExpr struct() {
        SQLStructExpr structExpr = new SQLStructExpr();
        accept(Token.LPAREN);
        aliasedItems(structExpr.getItems(), structExpr);
        if (lexer.token == Token.GTGT) {
            lexer.token = Token.RPAREN;
        } else {
            accept(Token.RPAREN);
        }
        return structExpr;
    }
}
