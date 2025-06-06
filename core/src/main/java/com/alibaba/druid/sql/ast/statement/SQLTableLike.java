package com.alibaba.druid.sql.ast.statement;

import com.alibaba.druid.sql.ast.SQLObjectImpl;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;

public class SQLTableLike extends SQLObjectImpl implements SQLTableElement {
    private SQLExprTableSource table;
    private boolean includeProperties;
    private boolean includeDistribution;
    private boolean excludeProperties;
    private boolean excludeDistribution;

    @Override
    protected void accept0(SQLASTVisitor v) {
        if (v.visit(this)) {
            acceptChild(v, table);
        }
        v.endVisit(this);
    }

    public SQLTableLike clone() {
        SQLTableLike x = new SQLTableLike();
        if (table != null) {
            x.setTable(table.clone());
        }

        return x;
    }

    public SQLExprTableSource getTable() {
        return table;
    }

    public void setTable(SQLExprTableSource x) {
        if (x != null) {
            x.setParent(this);
        }
        this.table = x;
    }

    public boolean isIncludeProperties() {
        return includeProperties;
    }

    public void setIncludeProperties(boolean includeProperties) {
        this.includeProperties = includeProperties;
    }

    public boolean isExcludeProperties() {
        return excludeProperties;
    }

    public void setExcludeProperties(boolean excludeProperties) {
        this.excludeProperties = excludeProperties;
    }

    public boolean isIncludeDistribution() {
        return includeDistribution;
    }

    public void setIncludeDistribution(boolean includeDistribution) {
        this.includeDistribution = includeDistribution;
    }

    public boolean isExcludeDistribution() {
        return excludeDistribution;
    }

    public void setExcludeDistribution(boolean excludeDistribution) {
        this.excludeDistribution = excludeDistribution;
    }
}
