package com.alibaba.druid;

import com.alibaba.druid.util.FnvHash;

public enum DbType {
    other(1 << 0),
    jtds(1 << 1),
    hsql(1 << 2),
    db2(1 << 3),
    postgresql(1 << 4),

    sqlserver(1 << 5),
    oracle(1 << 6),
    mysql(1 << 7),
    mariadb(1 << 8),
    derby(1 << 9),

    hive(1 << 10),
    h2(1 << 11),
    dm(1 << 12), // dm.jdbc.driver.DmDriver
    kingbase(1 << 13),
    gbase(1 << 14),

    oceanbase(1 << 15),
    informix(1 << 16),
    odps(1 << 17),
    teradata(1 << 18),
    phoenix(1 << 19),

    edb(1 << 20),
    kylin(1 << 21), // org.apache.kylin.jdbc.Driver
    sqlite(1 << 22),
    ads(1 << 23),
    presto(1 << 24),

    elastic_search(1 << 25), // com.alibaba.xdriver.elastic.jdbc.ElasticDriver
    hbase(1 << 26),
    drds(1 << 27),

    clickhouse(1 << 28),
    blink(1 << 29),

    @Deprecated
    antspark(1 << 30),

    spark(1 << 30),
    oceanbase_oracle(1 << 31),
    /**
     * Alibaba Cloud PolarDB-Oracle 1.0
     */
    polardb(1L << 32),

    ali_oracle(1L << 33),
    mock(1L << 34),
    sybase(1L << 35),
    highgo(1L << 36),
    /**
     * 非常成熟的开源mpp数据库
     */
    greenplum(1L << 37),
    /**
     * 华为的mpp数据库
     */
    gaussdb(1L << 38),

    trino(1L << 39),

    oscar(1L << 40),

    tidb(1L << 41),

    tydb(1L << 42),

    starrocks(1L << 43),

    goldendb(1L << 44),

    snowflake(1L << 45),

    redshift(1L << 46),

    hologres(1L << 47),

    bigquery(1L << 48),

    impala(1L << 49),

    doris(1L << 50),

    lealone(1L << 51),

    athena(1L << 52),

    polardbx(1L << 53),
    supersql(1L << 54),
    databricks(1L << 55),
    adb_mysql(1L << 56),
    /**
     * Alibaba Cloud PolarDB-Oracle 2.0
     */
    polardb2(1L << 57),
    synapse(1L << 58),

    ingres(0),
    cloudscape(0),
    timesten(0),
    as400(0),
    sapdb(0),
    kdb(0),
    log4jdbc(0),
    xugu(0),
    firebirdsql(0),
    JSQLConnect(0),
    JTurbo(0),
    interbase(0),
    pointbase(0),
    edbc(0),
    mimer(0),
    taosdata(0),
    sundb(0);

    public final long mask;
    public final long hashCode64;

    private DbType(long mask) {
        this.mask = mask;
        this.hashCode64 = FnvHash.hashCode64(name());
    }

    public static long of(DbType... types) {
        long value = 0;

        for (DbType type : types) {
            value |= type.mask;
        }

        return value;
    }

    public static DbType of(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        if ("aliyun_ads".equalsIgnoreCase(name)) {
            return ads;
        }

        if ("maxcompute".equalsIgnoreCase(name)) {
            return odps;
        }

        try {
            return valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPostgreSQLDbStyle(DbType dbType) {
        return dbType == DbType.postgresql || dbType == DbType.edb || dbType == DbType.greenplum || dbType == DbType.hologres;
    }
    public final boolean equals(String other) {
        return this == of(other);
    }
}
