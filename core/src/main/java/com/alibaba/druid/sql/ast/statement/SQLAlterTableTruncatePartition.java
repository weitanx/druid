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
package com.alibaba.druid.sql.ast.statement;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLObjectImpl;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;

import java.util.ArrayList;
import java.util.List;

public class SQLAlterTableTruncatePartition extends SQLObjectImpl implements SQLAlterTableItem {
    private final List<SQLName> partitions = new ArrayList<SQLName>(4);
    private final List<SQLExpr> partitionValues = new ArrayList<>(4);

    public List<SQLName> getPartitions() {
        return partitions;
    }

    public void addPartitionValue(SQLExpr partitionValue) {
        if (partitionValue != null) {
            partitionValue.setParent(this);
        }
        this.partitionValues.add(partitionValue);
    }
    public List<SQLExpr> getPartitionValues() {
        return partitionValues;
    }
    public void addPartition(SQLName partition) {
        if (partition != null) {
            partition.setParent(this);
        }
        this.partitions.add(partition);
    }

    @Override
    protected void accept0(SQLASTVisitor visitor) {
        if (visitor.visit(this)) {
            acceptChild(visitor, partitions);
        }
        visitor.endVisit(this);
    }
}
