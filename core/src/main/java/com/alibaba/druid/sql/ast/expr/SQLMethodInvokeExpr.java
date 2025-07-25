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
package com.alibaba.druid.sql.ast.expr;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitor;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.alibaba.druid.util.FnvHash;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SQLMethodInvokeExpr extends SQLExprImpl implements SQLReplaceable, Serializable {
    private static final long serialVersionUID = 1L;
    private boolean removeBrackets;

    protected final List<SQLExpr> arguments = new ArrayList<SQLExpr>();
    protected String methodName;
    protected long methodNameHashCode64;
    protected long hashCode64;
    protected SQLExpr owner;
    protected SQLExpr from;
    protected SQLExpr using;
    protected SQLExpr hasFor;
    protected String trimOption;
    protected transient SQLDataType resolvedReturnDataType;

    public SQLMethodInvokeExpr() {
        this.removeBrackets = false;
    }

    public SQLMethodInvokeExpr(String methodName) {
        this.methodName = methodName;
        this.removeBrackets = false;
    }

    public SQLMethodInvokeExpr(SQLIdentifierExpr methodName) {
        this.methodName = methodName.name;
        this.methodNameHashCode64 = methodName.hashCode64;
        this.setSource(methodName.getSourceLine(), methodName.getSourceColumn());
        this.removeBrackets = false;
    }

    public SQLMethodInvokeExpr(String methodName, long methodNameHashCode64) {
        this.methodName = methodName;
        this.methodNameHashCode64 = methodNameHashCode64;
        this.removeBrackets = false;
    }

    public SQLMethodInvokeExpr(String methodName, SQLExpr owner) {
        this.methodName = methodName;
        setOwner(owner);
        this.removeBrackets = false;
    }

    public SQLMethodInvokeExpr(String methodName, SQLExpr owner, SQLExpr... params) {
        this.methodName = methodName;
        setOwner(owner);
        for (SQLExpr param : params) {
            this.addArgument(param);
        }
        this.removeBrackets = false;
    }

    public SQLMethodInvokeExpr(String methodName, SQLExpr owner, List<SQLExpr> params) {
        this.methodName = methodName;
        setOwner(owner);
        for (SQLExpr param : params) {
            this.addArgument(param);
        }
        this.removeBrackets = false;
    }

    public long methodNameHashCode64() {
        if (methodNameHashCode64 == 0
                && methodName != null) {
            methodNameHashCode64 = FnvHash.hashCode64(methodName);
        }
        return methodNameHashCode64;
    }

    public long hashCode64() {
        if (hashCode64 == 0) {
            computeHashCode64();
        }

        return hashCode64;
    }

    protected void computeHashCode64() {
        long hash;
        if (owner instanceof SQLName) {
            hash = ((SQLName) owner).hashCode64();

            hash ^= '.';
            hash *= FnvHash.PRIME;
        } else if (owner == null) {
            hash = FnvHash.BASIC;
        } else {
            hash = FnvHash.fnv1a_64_lower(owner.toString());

            hash ^= '.';
            hash *= FnvHash.PRIME;
        }
        hash = FnvHash.hashCode64(hash, methodName);
        hashCode64 = hash;
    }

    public String getMethodName() {
        return this.methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
        this.methodNameHashCode64 = 0L;
    }

    /**
     * instead of getArguments
     *
     * @deprecated
     */
    public List<SQLExpr> getParameters() {
        return this.arguments;
    }

    public List<SQLExpr> getArguments() {
        return this.arguments;
    }

    public void setArgument(int i, SQLExpr arg) {
        if (arg != null) {
            arg.setParent(this);
        }
        this.arguments.set(i, arg);
    }

    public SQLExpr getArgument(int i) {
        if (i >= 0 && i < this.arguments.size()) {
            return this.arguments.get(i);
        }
        return null;
    }

    /**
     * deprecated, instead of addArgument
     *
     * @deprecated
     */
    public void addParameter(SQLExpr param) {
        if (param != null) {
            param.setParent(this);
        }
        this.arguments.add(param);
    }

    public void addArgument(SQLExpr arg) {
        if (arg != null) {
            arg.setParent(this);
        }
        this.arguments.add(arg);
    }

    public void addArguments(List<SQLExpr> args) {
        for (SQLExpr arg : args) {
            addArgument(arg);
        }
    }

    public SQLExpr getOwner() {
        return this.owner;
    }

    public void setOwner(SQLExpr owner) {
        if (owner != null) {
            owner.setParent(this);
        }
        this.owner = owner;
    }

    public SQLExpr getFrom() {
        return from;
    }

    public void setFrom(SQLExpr x) {
        if (x != null) {
            x.setParent(this);
        }
        this.from = x;
    }

    public void output(StringBuilder buf) {
        if (this.owner != null) {
            this.owner.output(buf);
            buf.append(".");
        }

        if (this.content != null) {
            this.content.output(buf);
            buf.append(' ');
        }
        buf.append(this.methodName);
        buf.append("(");
        for (int i = 0, size = this.arguments.size(); i < size; ++i) {
            if (i != 0) {
                buf.append(", ");
            }

            this.arguments.get(i).output(buf);
        }
        if (this.as != null) {
            buf.append(' ');
            this.as.output(buf);
        }
        buf.append(")");
    }

    @Override
    protected void accept0(SQLASTVisitor visitor) {
        if (visitor.visit(this)) {
            if (this.content != null) {
                acceptChild(visitor, this.content);
            }
            if (this.owner != null) {
                this.owner.accept(visitor);
            }

            for (SQLExpr arg : this.arguments) {
                if (arg != null) {
                    arg.accept(visitor);
                }
            }

            if (this.from != null) {
                this.from.accept(visitor);
            }

            if (this.using != null) {
                this.using.accept(visitor);
            }

            if (this.hasFor != null) {
                this.hasFor.accept(visitor);
            }
            if (this.as != null) {
                acceptChild(visitor, this.as);
            }
        }

        visitor.endVisit(this);
    }

    public List getChildren() {
        if (this.owner == null) {
            return this.arguments;
        }

        List<SQLObject> children = new ArrayList<SQLObject>();
        children.add(owner);
        children.addAll(this.arguments);
        return children;
    }

    protected void accept0(OracleASTVisitor visitor) {
        if (visitor.visit(this)) {
            if (this.content != null) {
                acceptChild(visitor, this.content);
            }
            if (this.owner != null) {
                this.owner.accept(visitor);
            }

            for (SQLExpr arg : this.arguments) {
                if (arg != null) {
                    arg.accept(visitor);
                }
            }

            if (this.from != null) {
                this.from.accept(visitor);
            }

            if (this.using != null) {
                this.using.accept(visitor);
            }

            if (this.hasFor != null) {
                this.hasFor.accept(visitor);
            }
            if (this.as != null) {
                acceptChild(visitor, this.as);
            }
        }

        visitor.endVisit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SQLMethodInvokeExpr that = (SQLMethodInvokeExpr) o;

        if (methodNameHashCode64() != that.methodNameHashCode64()) {
            return false;
        }
        if (owner != null ? !owner.equals(that.owner) : that.owner != null) {
            return false;
        }
        if (content != null ? !content.equals(that.content) : that.content != null) {
            return false;
        }
        if (as != null ? !as.equals(that.as) : that.as != null) {
            return false;
        }
        if (!arguments.equals(that.arguments)) {
            return false;
        }
        return from != null ? from.equals(that.from) : that.from == null;

    }

    @Override
    public int hashCode() {
        int result = (int) (methodNameHashCode64() ^ (methodNameHashCode64() >>> 32));
        result = 31 * result + (owner != null ? owner.hashCode() : 0);
        result = 31 * result + arguments.hashCode();
        result = 31 * result + (from != null ? from.hashCode() : 0);
        return result;
    }

    public SQLMethodInvokeExpr clone() {
        SQLMethodInvokeExpr x = new SQLMethodInvokeExpr();
        cloneTo(x);
        return x;
    }

    public void cloneTo(SQLMethodInvokeExpr x) {
        super.cloneTo(x);
        x.methodName = methodName;

        if (owner != null) {
            x.setOwner(owner.clone());
        }

        if (content != null) {
            x.setContent(content);
        }
        for (SQLExpr arg : arguments) {
            x.addArgument(arg.clone());
        }

        if (from != null) {
            x.setFrom(from.clone());
        }

        if (using != null) {
            x.setUsing(using.clone());
        }

        if (trimOption != null) {
            x.setTrimOption(trimOption);
        }

        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof SQLObject) {
                    value = ((SQLObject) value).clone();
                }
                x.putAttribute(key, value);
            }
        }
        x.setRemoveBrackets(removeBrackets);
    }

    @Override
    public boolean replace(SQLExpr expr, SQLExpr target) {
        if (target == null) {
            return false;
        }

        for (int i = 0; i < arguments.size(); ++i) {
            if (arguments.get(i) == expr) {
                arguments.set(i, target);
                target.setParent(this);
                return true;
            }
        }

        if (from == expr) {
            setFrom(target);
            return true;
        }

        if (using == expr) {
            setUsing(target);
            return true;
        }

        if (hasFor == expr) {
            setFor(target);
            return true;
        }

        return false;
    }

    public boolean match(String owner, String function) {
        if (function == null) {
            return false;
        }

        if (!SQLUtils.nameEquals(function, methodName)) {
            return false;
        }

        if (owner == null && this.owner == null) {
            return true;
        }

        if (owner == null || this.owner == null) {
            return false;
        }

        if (this.owner instanceof SQLIdentifierExpr) {
            return SQLUtils.nameEquals(((SQLIdentifierExpr) this.owner).name, owner);
        }

        return false;
    }

    public SQLDataType computeDataType() {
        if (resolvedReturnDataType != null) {
            return resolvedReturnDataType;
        }

        long nameHash = this.methodNameHashCode64();
        if (nameHash == FnvHash.Constants.TO_DATE
                || nameHash == FnvHash.Constants.ADD_MONTHS
        ) {
            return resolvedReturnDataType = SQLDateExpr.DATA_TYPE;
        }
        if (nameHash == FnvHash.Constants.DATE_PARSE) {
            return resolvedReturnDataType = SQLTimestampExpr.DATA_TYPE;
        }
        if (nameHash == FnvHash.Constants.CURRENT_TIME
                || nameHash == FnvHash.Constants.CURTIME) {
            return resolvedReturnDataType = SQLTimeExpr.DATA_TYPE;
        }

        if (nameHash == FnvHash.Constants.BIT_COUNT
                || nameHash == FnvHash.Constants.ROW_NUMBER) {
            return resolvedReturnDataType = new SQLDataTypeImpl("BIGINT");
        }

        if (arguments.size() == 1) {
            if (nameHash == FnvHash.Constants.TRUNC) {
                return resolvedReturnDataType = arguments.get(0).computeDataType();
            }
        } else if (arguments.size() == 2) {
            SQLExpr param0 = arguments.get(0);
            SQLExpr param1 = arguments.get(1);

            if (nameHash == FnvHash.Constants.ROUND) {
                SQLDataType dataType = param0.computeDataType();
                if (dataType != null) {
                    return dataType;
                }
            } else if (nameHash == FnvHash.Constants.NVL
                    || nameHash == FnvHash.Constants.IFNULL
                    || nameHash == FnvHash.Constants.ISNULL
                    || nameHash == FnvHash.Constants.COALESCE) {
                SQLDataType dataType = param0.computeDataType();
                if (dataType != null) {
                    return dataType;
                }

                return param1.computeDataType();
            }

            if (nameHash == FnvHash.Constants.MOD) {
                return resolvedReturnDataType = SQLIntegerExpr.DATA_TYPE;
            }
        }

//        if (nameHash == FnvHash.Constants.ROUND) {
//            return resolvedReturnDataType = SQLDecimalExpr.DATA_TYPE;
//        }

        if (nameHash == FnvHash.Constants.STDDEV_SAMP) {
            return resolvedReturnDataType = SQLNumberExpr.DATA_TYPE_DOUBLE;
        }

        if (nameHash == FnvHash.Constants.CONCAT
                || nameHash == FnvHash.Constants.SUBSTR
                || nameHash == FnvHash.Constants.SUBSTRING) {
            return resolvedReturnDataType = SQLCharExpr.DATA_TYPE;
        }

        if (nameHash == FnvHash.Constants.YEAR
                || nameHash == FnvHash.Constants.MONTH
                || nameHash == FnvHash.Constants.DAY
                || nameHash == FnvHash.Constants.HOUR
                || nameHash == FnvHash.Constants.MINUTE
                || nameHash == FnvHash.Constants.SECOND
                || nameHash == FnvHash.Constants.PERIOD_ADD
                || nameHash == FnvHash.Constants.PERIOD_DIFF
        ) {
            return resolvedReturnDataType = new SQLDataTypeImpl("INT");
        }

        if (nameHash == FnvHash.Constants.GROUPING) {
            return resolvedReturnDataType = new SQLDataTypeImpl("INT");
        }

        if (nameHash == FnvHash.Constants.JSON_EXTRACT_SCALAR
                || nameHash == FnvHash.Constants.FORMAT_DATETIME
                || nameHash == FnvHash.Constants.DATE_FORMAT
        ) {
            return resolvedReturnDataType = SQLCharExpr.DATA_TYPE;
        }

        if (nameHash == FnvHash.Constants.DATE_ADD
                || nameHash == FnvHash.Constants.DATE_SUB
                || nameHash == FnvHash.Constants.DATE
                || nameHash == FnvHash.Constants.STR_TO_DATE
                || nameHash == FnvHash.Constants.CURRENT_DATE) {
            return resolvedReturnDataType = SQLDateExpr.DATA_TYPE;
        }

        if (nameHash == FnvHash.Constants.UNIX_TIMESTAMP) {
            return resolvedReturnDataType = SQLIntegerExpr.DATA_TYPE;
        }

        if (nameHash == FnvHash.Constants.TIME) {
            return resolvedReturnDataType = new SQLDataTypeImpl("VARCHAR");
        }

        if (nameHash == FnvHash.Constants.SYSDATE
                || nameHash == FnvHash.Constants.CURRENT_TIMESTAMP
                || nameHash == FnvHash.Constants.SYSTIMESTAMP) {
            return resolvedReturnDataType = SQLTimestampExpr.DATA_TYPE;
        }

        return null;
    }

    public SQLExpr getUsing() {
        return using;
    }

    public void setUsing(SQLExpr x) {
        if (x != null) {
            x.setParent(this);
        }
        this.using = x;
    }

    public SQLExpr getFor() {
        return hasFor;
    }

    public void setFor(SQLExpr x) {
        if (x != null) {
            x.setParent(this);
        }
        this.hasFor = x;
    }

    public String getTrimOption() {
        return trimOption;
    }

    public void setTrimOption(String trimOption) {
        this.trimOption = trimOption;
    }

    public SQLDataType getResolvedReturnDataType() {
        return resolvedReturnDataType;
    }

    public void setResolvedReturnDataType(SQLDataType resolvedReturnDataType) {
        this.resolvedReturnDataType = resolvedReturnDataType;
    }

    private SQLExpr as;
    private SQLExpr content;

    public SQLExpr getAs() {
        return as;
    }

    public void setAs(SQLExpr x) {
        if (x != null) {
            x.setParent(this);
        }
        this.as = x;
     }

    public SQLExpr getContent() {
        return content;
    }

    public void setContent(SQLExpr x) {
        if (x != null) {
            x.setParent(this);
        }
        this.content = x;
    }

    public boolean isRemoveBrackets() {
        return removeBrackets;
    }

    public void setRemoveBrackets(boolean removeBrackets) {
        this.removeBrackets = removeBrackets;
    }
}
