package com.wugui.datax.admin.tool.query;

import cn.hutool.core.util.StrUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.wugui.datatx.core.util.Constants;
import com.wugui.datax.admin.core.util.LocalCacheUtil;
import com.wugui.datax.admin.entity.JobDatasource;
import com.wugui.datax.admin.tool.database.ColumnInfo;
import com.wugui.datax.admin.tool.database.DasColumn;
import com.wugui.datax.admin.tool.database.TableInfo;
import com.wugui.datax.admin.tool.meta.DatabaseInterface;
import com.wugui.datax.admin.tool.meta.DatabaseMetaFactory;
import com.wugui.datax.admin.util.AESUtil;
import com.wugui.datax.admin.util.JdbcConstants;
import com.wugui.datax.admin.util.JdbcUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 抽象查询工具
 *
 * @author zhouhongfa@gz-yibo.com
 * @ClassName BaseQueryTool
 * @Version 1.0
 * @since 2019/7/18 9:22
 */
public abstract class BaseQueryTool implements QueryToolInterface {

    protected static final Logger logger = LoggerFactory.getLogger(BaseQueryTool.class);
    /**
     * 用于获取查询语句
     */
    private DatabaseInterface sqlBuilder;

//    private DataSource datasource;

    private Connection connection;
    /**
     * 当前数据库名
     */
    private String currentSchema;
    private String currentDatabase;

    /**
     * 构造方法
     *
     * @param jobDatasource
     */
    BaseQueryTool(JobDatasource jobDatasource) throws SQLException {
//        getDataSource(jobDatasource);
        if (LocalCacheUtil.get(jobDatasource.getDatasourceName()) == null) {
            getDataSource(jobDatasource);
        } else {
            this.connection = (Connection) LocalCacheUtil.get(jobDatasource.getDatasourceName());
            // 因为jtds不支持。isValid方法的调用（Caused by: java.lang.AbstractMethodError: null）
            // 但是此处暂时不确定怎么处理比较好
//            if (!this.connection.isValid(500)) {
//                LocalCacheUtil.remove(jobDatasource.getDatasourceName());
//                getDataSource(jobDatasource);
//            }

            // 暂时使用这种方式代替
            try{
                connection.getMetaData();
            }catch (Exception e){
                logger.error("缓存的数据源不可用了!", e);
                LocalCacheUtil.remove(jobDatasource.getDatasourceName());
                getDataSource(jobDatasource);
            }
        }
        sqlBuilder = DatabaseMetaFactory.getByDbType(jobDatasource.getDatasource());
        currentSchema = getSchema(jobDatasource.getJdbcUsername());
        currentDatabase = jobDatasource.getDatasource();
        LocalCacheUtil.set(jobDatasource.getDatasourceName(), this.connection, 4 * 60 * 60 * 1000);
    }

    private void getDataSource(JobDatasource jobDatasource) throws SQLException {
        String userName = AESUtil.decrypt(jobDatasource.getJdbcUsername());

        try{
            if(StringUtils.isNotBlank(jobDatasource.getJdbcDriverName())){
                URL path = new URL(jobDatasource.getJdbcDriverName());
                URLClassLoader ucl = new URLClassLoader(new URL[] { path });
                Driver d = (Driver)Class.forName(jobDatasource.getJdbcDriverClass(), true, ucl).newInstance();
                DriverShim driver = new DriverShim(d);
                DriverManager.registerDriver(driver);
                this.connection = DriverManager.getConnection(jobDatasource.getJdbcUrl(),userName,AESUtil.decrypt(jobDatasource.getJdbcPassword()));
            }else{
                Class.forName(jobDatasource.getJdbcDriverClass());
                DriverManager.setLoginTimeout(30000);
                this.connection = DriverManager.getConnection(jobDatasource.getJdbcUrl(),userName,AESUtil.decrypt(jobDatasource.getJdbcPassword()));
            }
        }catch (Exception e){
            logger.error("jdbc出错!", e);
        }

//        //这里默认使用 hikari 数据源
//        HikariDataSource dataSource = new HikariDataSource();
//        dataSource.setUsername(userName);
//        dataSource.setPassword(AESUtil.decrypt(jobDatasource.getJdbcPassword()));
//        dataSource.setJdbcUrl(jobDatasource.getJdbcUrl());
//        dataSource.setDriverClassName(jobDatasource.getJdbcDriverClass());
//        dataSource.setMaximumPoolSize(1);
//        dataSource.setMinimumIdle(0);
//        dataSource.setConnectionTimeout(30000);
//        // 这个位置暂时是为了适配jtds,当然也可以根据数据库类型我们给所有的数据库类型都加上testSql
//        if(jobDatasource.getJdbcDriverClass().contains("jtds")){
//            dataSource.setConnectionTestQuery("SELECT 1");
//        }
//        this.datasource = dataSource;
//        this.connection = this.datasource.getConnection();
    }

    //根据connection获取schema
    private String getSchema(String jdbcUsername) {
        String res = null;
        try {
            res = connection.getCatalog();
        } catch (SQLException e) {
            try {
                res = connection.getSchema();
            } catch (SQLException e1) {
                logger.error("[SQLException getSchema Exception] --> "
                        + "the exception message is:" + e1.getMessage());
            }
            logger.error("[getSchema Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        // 如果res是null，则将用户名当作 schema
        if (StrUtil.isBlank(res) && StringUtils.isNotBlank(jdbcUsername)) {
            res = jdbcUsername.toUpperCase();
        }
        return res;
    }

    @Override
    public TableInfo buildTableInfo(String tableName) {
        //获取表信息
        List<Map<String, Object>> tableInfos = this.getTableInfo(tableName);
        if (tableInfos.isEmpty()) {
            throw new NullPointerException("查询出错! ");
        }

        TableInfo tableInfo = new TableInfo();
        //表名，注释
        List tValues = new ArrayList(tableInfos.get(0).values());

        tableInfo.setName(StrUtil.toString(tValues.get(0)));
        tableInfo.setComment(StrUtil.toString(tValues.get(1)));


        //获取所有字段
        List<ColumnInfo> fullColumn = getColumns(tableName);
        tableInfo.setColumns(fullColumn);

        //获取主键列
        List<String> primaryKeys = getPrimaryKeys(tableName);
        logger.info("主键列为：{}", primaryKeys);

        //设置ifPrimaryKey标志
        fullColumn.forEach(e -> {
            if (primaryKeys.contains(e.getName())) {
                e.setIfPrimaryKey(true);
            } else {
                e.setIfPrimaryKey(false);
            }
        });
        return tableInfo;
    }

    //无论怎么查，返回结果都应该只有表名和表注释，遍历map拿value值即可
    @Override
    public List<Map<String, Object>> getTableInfo(String tableName) {
        String sqlQueryTableNameComment = sqlBuilder.getSQLQueryTableNameComment();
        logger.info(sqlQueryTableNameComment);
        List<Map<String, Object>> res = null;
        try {
            res = JdbcUtils.executeQuery(connection, sqlQueryTableNameComment, ImmutableList.of(currentSchema, tableName));
        } catch (SQLException e) {
            logger.error("[getTableInfo Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    @Override
    public List<Map<String, Object>> getTables() {
        String sqlQueryTables = sqlBuilder.getSQLQueryTables();
        logger.info(sqlQueryTables);
        List<Map<String, Object>> res = null;
        try {
            res = JdbcUtils.executeQuery(connection, sqlQueryTables, ImmutableList.of(currentSchema));
        } catch (SQLException e) {
            logger.error("[getTables Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    @Override
    public List<ColumnInfo> getColumns(String tableName) {

        List<ColumnInfo> fullColumn = Lists.newArrayList();
        //获取指定表的所有字段
        try {
            //获取查询指定表所有字段的sql语句
            String querySql = sqlBuilder.getSQLQueryFields(tableName);
            logger.info("querySql: {}", querySql);

            //获取所有字段
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(querySql);
            ResultSetMetaData metaData = resultSet.getMetaData();

            List<DasColumn> dasColumns = buildDasColumn(tableName, metaData);
            statement.close();

            //构建 fullColumn
            fullColumn = buildFullColumn(dasColumns);

        } catch (SQLException e) {
            logger.error("[getColumns Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return fullColumn;
    }

    private List<ColumnInfo> buildFullColumn(List<DasColumn> dasColumns) {
        List<ColumnInfo> res = Lists.newArrayList();
        dasColumns.forEach(e -> {
            ColumnInfo columnInfo = new ColumnInfo();
            columnInfo.setName(e.getColumnName());
            columnInfo.setComment(e.getColumnComment());
            columnInfo.setType(e.getColumnTypeName());
            columnInfo.setIfPrimaryKey(e.isIsprimaryKey());
            columnInfo.setIsnull(e.getIsNull());
            res.add(columnInfo);
        });
        return res;
    }

    //构建DasColumn对象
    private List<DasColumn> buildDasColumn(String tableName, ResultSetMetaData metaData) {
        List<DasColumn> res = Lists.newArrayList();
        try {
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                DasColumn dasColumn = new DasColumn();
                dasColumn.setColumnClassName(metaData.getColumnClassName(i));
                dasColumn.setColumnTypeName(metaData.getColumnTypeName(i));
                dasColumn.setColumnName(metaData.getColumnName(i));
                dasColumn.setIsNull(metaData.isNullable(i));

                res.add(dasColumn);
            }

            Statement statement = connection.createStatement();

            if (currentDatabase.equals(JdbcConstants.MYSQL) || currentDatabase.equals(JdbcConstants.ORACLE)) {
                DatabaseMetaData databaseMetaData = connection.getMetaData();

                ResultSet resultSet = databaseMetaData.getPrimaryKeys(null, null, tableName);

                while (resultSet.next()) {
                    String name = resultSet.getString("COLUMN_NAME");
                    res.forEach(e -> {
                        if (e.getColumnName().equals(name)) {
                            e.setIsprimaryKey(true);

                        } else {
                            e.setIsprimaryKey(false);
                        }
                    });
                }

                res.forEach(e -> {
                    String sqlQueryComment = sqlBuilder.getSQLQueryComment(currentSchema, tableName, e.getColumnName());
                    //查询字段注释
                    try {
                        ResultSet resultSetComment = statement.executeQuery(sqlQueryComment);
                        while (resultSetComment.next()) {
                            e.setColumnComment(resultSetComment.getString(1));
                        }
                        JdbcUtils.close(resultSetComment);
                    } catch (SQLException e1) {
                        logger.error("[buildDasColumn executeQuery Exception] --> "
                                + "the exception message is:" + e1.getMessage());
                    }
                });
            }

            JdbcUtils.close(statement);
        } catch (SQLException e) {
            logger.error("[buildDasColumn Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    //获取指定表的主键，可能是多个，所以用list
    private List<String> getPrimaryKeys(String tableName) {
        List<String> res = Lists.newArrayList();
        String sqlQueryPrimaryKey = sqlBuilder.getSQLQueryPrimaryKey();
        try {
            List<Map<String, Object>> pkColumns = JdbcUtils.executeQuery(connection, sqlQueryPrimaryKey, ImmutableList.of(currentSchema, tableName));
            //返回主键名称即可
            pkColumns.forEach(e -> res.add((String) new ArrayList<>(e.values()).get(0)));
        } catch (SQLException e) {
            logger.error("[getPrimaryKeys Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return res;
    }

    @Override
    public List<String> getColumnNames(String tableName, String datasource) {

        List<String> res = Lists.newArrayList();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            //获取查询指定表所有字段的sql语句
            String querySql = sqlBuilder.getSQLQueryFields(tableName);
            logger.info("querySql: {}", querySql);

            //获取所有字段
            stmt = connection.createStatement();
            rs = stmt.executeQuery(querySql);
            ResultSetMetaData metaData = rs.getMetaData();

            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                if (JdbcConstants.HIVE.equals(datasource)) {
                    if (columnName.contains(Constants.SPLIT_POINT)) {
                        res.add(i - 1 + Constants.SPLIT_SCOLON + columnName.substring(columnName.indexOf(Constants.SPLIT_POINT) + 1) + Constants.SPLIT_SCOLON + metaData.getColumnTypeName(i));
                    } else {
                        res.add(i - 1 + Constants.SPLIT_SCOLON + columnName + Constants.SPLIT_SCOLON + metaData.getColumnTypeName(i));
                    }
                } else {
                    res.add(columnName);
                }

            }
        } catch (SQLException e) {
            logger.error("[getColumnNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return res;
    }

    @Override
    public List<String> getTableNames(String tableSchema) {
        List<String> tables = new ArrayList<String>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLQueryTables(tableSchema);
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String tableName = rs.getString(1);
                tables.add(tableName);
            }
            tables.sort(Comparator.naturalOrder());
        } catch (SQLException e) {
            logger.error("[getTableNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return tables;
    }

    @Override
    public List<String> getTableNames() {
        List<String> tables = new ArrayList<String>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLQueryTables();
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String tableName = rs.getString(1);
                tables.add(tableName);
            }
        } catch (SQLException e) {
            logger.error("[getTableNames Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return tables;
    }

    public Boolean dataSourceTest() {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData.getDatabaseProductName().length() > 0) {
                return true;
            }
        } catch (SQLException e) {
            logger.error("[dataSourceTest Exception] --> "
                    + "the exception message is:" + e.getMessage());
        }
        return false;
    }


    protected String getSQLQueryTables(String tableSchema) {
        return sqlBuilder.getSQLQueryTables(tableSchema);
    }

    /**
     * 不需要其他参数的可不重写
     *
     * @return
     */
    protected String getSQLQueryTables() {
        return sqlBuilder.getSQLQueryTables();
    }

    @Override
    public List<String> getColumnsByQuerySql(String querySql) throws SQLException {

        List<String> res = Lists.newArrayList();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            querySql = querySql.replace(";", "");
            //拼装sql语句，在后面加上 where 1=0 即可
            String sql = querySql.concat(" where 1=0");
            //判断是否已有where，如果是，则加 and 1=0
            //从最后一个 ) 开始找 where，或者整个语句找
            if (querySql.contains(")")) {
                if (querySql.substring(querySql.indexOf(")")).contains("where")) {
                    sql = querySql.concat(" and 1=0");
                }
            } else {
                if (querySql.contains("where")) {
                    sql = querySql.concat(" and 1=0");
                }
            }
            //获取所有字段
            stmt = connection.createStatement();
            rs = stmt.executeQuery(sql);
            ResultSetMetaData metaData = rs.getMetaData();

            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                res.add(metaData.getColumnName(i));
            }
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return res;
    }

    @Override
    public Long getMaxIdVal(String tableName, String primaryKey) {
        Statement stmt = null;
        ResultSet rs = null;
        Long maxVal = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLMaxID(tableName, primaryKey);
            rs = stmt.executeQuery(sql);
            rs.next();
            maxVal = rs.getLong(1);
        } catch (SQLException e) {
            logger.error("[getMaxIdVal Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }


        return maxVal;
    }

    private String getSQLMaxID(String tableName, String primaryKey) {
        return sqlBuilder.getMaxId(tableName, primaryKey);
    }

    public void executeCreateTableSql(String querySql) {
        if (StringUtils.isBlank(querySql)) {
            return;
        }
        Statement stmt = null;
        try {
            stmt = connection.createStatement();
            stmt.executeUpdate(querySql);
        } catch (SQLException e) {
            logger.error("[executeCreateTableSql Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(stmt);
        }
    }

    public List<String> getTableSchema() {
        List<String> schemas = new ArrayList<>();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLQueryTableSchema();
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String tableName = rs.getString(1);
                schemas.add(tableName);
            }
        } catch (SQLException e) {
            logger.error("[getTableSchemas Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return schemas;
    }

    protected String getSQLQueryTableSchema() {
        return sqlBuilder.getSQLQueryTableSchema();
    }


    /**
     * 获取表的DDL语句
     * @param tableName
     * @return
     */
    public String getTableDDL(String tableName){
        String ddl = "";
        String querySql = "show create table " + tableName;
        if (StringUtils.isNotBlank(tableName)) {
            Statement stmt = null;
            try {
                stmt = connection.createStatement();
                ResultSet result = stmt.executeQuery(querySql);
                while(result.next()){
                    ddl += result.getString(1);
                }
            } catch (SQLException e) {
                logger.error("[executeCreateTableSql Exception] --> "
                        + "the exception message is:" + e.getMessage());
            } finally {
                JdbcUtils.close(stmt);
            }
        }
        return ddl;
    }

    public Date getMaxTime(String tableName, String primaryKey){
        Statement stmt = null;
        ResultSet rs = null;
        Date maxVal = null;
        try {
            stmt = connection.createStatement();
            //获取sql
            String sql = getSQLMaxID(tableName, primaryKey);
            rs = stmt.executeQuery(sql);
            rs.next();
            maxVal = rs.getTimestamp(1);
        } catch (SQLException e) {
            logger.error("[getMaxIdVal Exception] --> "
                    + "the exception message is:" + e.getMessage());
        } finally {
            JdbcUtils.close(rs);
            JdbcUtils.close(stmt);
        }
        return maxVal;
    }
}
