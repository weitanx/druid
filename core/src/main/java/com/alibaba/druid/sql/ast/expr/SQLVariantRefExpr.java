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

import com.alibaba.druid.sql.ast.SQLExprImpl;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SQLVariantRefExpr extends SQLExprImpl {
    private String name;

    private boolean global;
    private boolean session;
    private boolean templateParameter;
    private boolean hasPrefixComma;

    private int index = -1;

    public SQLVariantRefExpr(String name) {
        this.name = name;
        if (name.startsWith("${") && name.endsWith("}")) {
            this.templateParameter = true;
        } else {
            this.templateParameter = false;
        }
        this.hasPrefixComma = true;
    }

    public SQLVariantRefExpr(String name, SQLObject parent) {
        this.name = name;
        this.parent = parent;
        if (name.startsWith("${") && name.endsWith("}")) {
            this.templateParameter = true;
        } else {
            this.templateParameter = false;
        }
        this.hasPrefixComma = true;
    }

    public SQLVariantRefExpr(String name, boolean global) {
        this(name, global, false);
    }

    public SQLVariantRefExpr(String name, boolean global, boolean session) {
        this.name = name;
        this.global = global;
        this.session = session;
    }

    public SQLVariantRefExpr() {
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isTemplateParameter() {
        return templateParameter;
    }

    public void setTemplateParameter(boolean templateParameter) {
        this.templateParameter = templateParameter;
    }

    public boolean isHasPrefixComma() {
        return hasPrefixComma;
    }

    public void setHasPrefixComma(boolean hasPrefixComma) {
        this.hasPrefixComma = hasPrefixComma;
    }

    public void output(StringBuilder buf) {
        buf.append(this.name);
    }

    @Override
    protected void accept0(SQLASTVisitor visitor) {
        visitor.visit(this);

        visitor.endVisit(this);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof SQLVariantRefExpr)) {
            return false;
        }
        SQLVariantRefExpr other = (SQLVariantRefExpr) obj;
        if (name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!name.equals(other.name)) {
            return false;
        }
        return true;
    }

    public boolean isGlobal() {
        return global;
    }

    public void setGlobal(boolean global) {
        this.global = global;
    }

    public boolean isSession() {
        return session;
    }

    public void setSession(boolean session) {
        this.session = session;
    }

    public SQLVariantRefExpr clone() {
        SQLVariantRefExpr var = new SQLVariantRefExpr(name, global, session);

        if (attributes != null) {
            var.attributes = new HashMap<String, Object>(attributes.size());
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();

                if (v instanceof SQLObject) {
                    var.attributes.put(k, ((SQLObject) v).clone());
                } else {
                    var.attributes.put(k, v);
                }
            }
        }

        var.index = index;
        return var;
    }

    @Override
    public List<SQLObject> getChildren() {
        return Collections.emptyList();
    }
}
