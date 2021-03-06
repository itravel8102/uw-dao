package uw.dao.gencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.dao.DaoFactory;
import uw.dao.DataSet;
import uw.dao.TransactionException;
import uw.dao.connectionpool.ConnectionManager;
import uw.dao.util.DaoStringUtils;

import java.sql.*;
import java.util.*;

/**
 * Oracle表生成信息处理.
 *
 * @author liliang
 * @since 2017/9/11
 */
public class OracleDataMetaImpl implements TableMetaInterface {

    /**
     * 日志.
     */
    private static final Logger logger = LoggerFactory.getLogger(OracleDataMetaImpl.class);

    /**
     * 连接名.
     */
    private String CONN_NAME = null;

    /**
     * Oracle schema,默认等于连接名 TODO.
     */
    private String SCHEMA = null;

    /**
     * DAOFactory对象.
     */
    private final DaoFactory daoFactory = DaoFactory.getInstance();

    /**
     * 构造函数.
     *
     * @param CONN_NAME 连接名
     * @param SCHEMA    Oracle schema
     */
    public OracleDataMetaImpl(String CONN_NAME, String SCHEMA) {
        this.CONN_NAME = CONN_NAME;
        this.SCHEMA = SCHEMA == null || SCHEMA.equals("") ? CONN_NAME.toUpperCase() : SCHEMA.toUpperCase();
    }

    /**
     * 获取连接名.
     *
     * @return 连接名
     */
    @Override
    public String getConnName() {
        return CONN_NAME;
    }

    /**
     * 获得数据库链接.
     *
     * @return Connection对象
     */
    @Override
    public Connection getConnection() throws SQLException {
        if (CONN_NAME == null || CONN_NAME.equals("")) {
            return ConnectionManager.getConnection();
        } else {
            return ConnectionManager.getConnection(CONN_NAME);
        }
    }

    /**
     * 获取数据库中的表名称与视图名称.
     *
     * @param tables 表集合
     * @return 表信息的列表
     */
    @Override
    public List<MetaTableInfo> getTablesAndViews(Set<String> tables) {
        List<MetaTableInfo> list = new ArrayList<MetaTableInfo>();
        for (String tableName : tables) {
            try {

                String sql = "SELECT\n" + "	c.TABLE_NAME,\n" + "	c.TABLE_TYPE,\n"
                        + "	DECODE(nvl(c.COMMENTS,'null'),'null','',c.COMMENTS) as REMARKS\n" + "FROM\n"
                        + "	all_tables T,\n" + "	user_tab_comments c\n" + "WHERE\n" + "	T . OWNER = '" + SCHEMA
                        + "'\n" + "AND c.table_name = T .table_name\n" + "AND c.table_name = upper('" + tableName
                        + "')";
                DaoFactory daoFactory = DaoFactory.getInstance();
                DataSet dataSet = daoFactory.queryForDataSet(CONN_NAME, sql);
                while (dataSet.next()) {
                    MetaTableInfo meta = new MetaTableInfo();
                    meta.setTableName(tableName.toLowerCase());
                    dataSet.getString("TABLE_NAME");
                    meta.setEntityName(DaoStringUtils.toClearCase(tableName));
                    meta.setTableType(dataSet.getString("TABLE_TYPE").toLowerCase());
                    meta.setRemarks(dataSet.getString("REMARKS"));
                    if (meta.getRemarks() == null || "".equals(meta.getRemarks())) {
                        meta.setRemarks(tableName);
                    }
                    if (tables.size() > 0) {
                        if (tables.contains(tableName.toLowerCase())) {
                            list.add(meta);
                        }
                    } else {
                        list.add(meta);
                    }
                }
            } catch (TransactionException e) {
                logger.error(e.getMessage(), e);
            }
        }

        return list;
    }

    /**
     * 利用表名和数据库用户名查询出该表对应的字段类型.
     *
     * @param tableName 表名
     * @return 表字段信息的列表
     * @throws Exception
     */
    @Override
    public List<MetaColumnInfo> getColumnList(String tableName, List<MetaPrimaryKeyInfo> pklist) {
        Connection conn = null;
        ResultSet rs = null;
        List<MetaColumnInfo> list = new ArrayList<MetaColumnInfo>();
        // 加载主键列表
        HashSet<String> pset = new HashSet<String>();
        for (MetaPrimaryKeyInfo pk : pklist) {
            pset.add(pk.getColumnName());
        }
        try {
            HashMap<String, String> remarkhs = new HashMap<String, String>();
            // 查询注释
            DaoFactory daoFactory = DaoFactory.getInstance();
            DataSet dataSet = daoFactory.queryForDataSet(CONN_NAME, "select  t.table_name,t.column_name,t.comments from USER_COL_COMMENTS t where t.TABLE_NAME=upper('" + tableName + "')");
            while (dataSet.next()) {
                remarkhs.put(dataSet.getString("column_name"), dataSet.getString("comments"));
            }
            conn = getConnection();
            DatabaseMetaData metaData = conn.getMetaData();
            rs = metaData.getColumns(null, conn.getSchema(), tableName.toUpperCase(), null);
            while (rs.next()) {
                MetaColumnInfo meta = new MetaColumnInfo();
                meta.setColumnName(rs.getString("COLUMN_NAME").toLowerCase()); // 列名
                meta.setPropertyName(DaoStringUtils.toClearCase(meta.getColumnName()));
                meta.setDataType(rs.getInt("DATA_TYPE")); // 字段数据类型(对应java.sql.Types中的常量)
                meta.setTypeName(rs.getString("TYPE_NAME").toLowerCase()); // 字段类型名称(例如：VACHAR2)
                meta.setColumnSize(rs.getInt("COLUMN_SIZE")); // 列的大小
                meta.setRemarks(remarkhs.get(rs.getString("COLUMN_NAME"))); // 描述列的注释
                meta.setIsNullable(rs.getString("IS_NULLABLE").equals("YES") ? "true" : "false"); // 确定列是否包括
                // null
                meta.setIsAutoIncrement(rs.getString("IS_AUTOINCREMENT").equals("YES") ? "true" : null); // 确定列是否包括
                // null
                if (pset.contains(meta.getColumnName())) {
                    meta.setIsPrimaryKey("true");
                }
                switch (meta.getDataType()) {
                    case Types.NUMERIC:
                        if (rs.getInt("COLUMN_SIZE") < 4) {
                            meta.setPropertyType("int");
                        } else if (rs.getInt("COLUMN_SIZE") < 10) {
                            meta.setPropertyType("int");
                        } else if (rs.getInt("COLUMN_SIZE") < 18) {
                            meta.setPropertyType("long");
                        } else {
                            meta.setPropertyType("java.math.BigDecimal");
                        }
                        break;
                    case Types.VARCHAR:
                        meta.setPropertyType("String");
                        break;
                    case Types.CLOB:
                        meta.setPropertyType("String");
                        break;
                    case Types.DATE:
                        meta.setPropertyType("java.util.Date");
                        break;
                    case Types.TIME:
                        meta.setPropertyType("java.util.Date");
                        break;
                    case Types.TIMESTAMP:
                        meta.setPropertyType("java.util.Date");
                        break;
                    case Types.BIGINT:
                        meta.setPropertyType("long");
                        break;
                    case Types.INTEGER:
                        meta.setPropertyType("int");
                        break;
                    case Types.SMALLINT:
                        meta.setPropertyType("int");
                        break;
                    case Types.TINYINT:
                        meta.setPropertyType("int");
                        break;
                    case Types.FLOAT:
                        meta.setPropertyType("float");
                        break;
                    case Types.DOUBLE:
                        meta.setPropertyType("double");
                        break;
                    case Types.BIT:
                        meta.setPropertyType("int");
                        break;

                    default:
                        meta.setPropertyType("Object");
                }
                list.add(meta);
            }
            rs.close();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        return list;
    }

    /**
     * 获得主键名.
     *
     * @param tableName 表名
     * @return 主键列表
     * @throws Exception 异常
     */
    @Override
    public List<MetaPrimaryKeyInfo> getPrimaryKey(String tableName) {
        List<MetaPrimaryKeyInfo> list = new ArrayList<MetaPrimaryKeyInfo>();
        try {
            String sql = "SELECT\n" + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"OWNER\",\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"CONSTRAINT_NAME\" PK_NAME,\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"TABLE_NAME\",\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"COLUMN_NAME\" COLUMN_NAME\n" + "FROM\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\"\n" + "JOIN \"SYS\".\"ALL_CONSTRAINTS\" ON (\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"OWNER\" = \"SYS\".\"ALL_CONSTRAINTS\".\"OWNER\"\n"
                    + "	AND \"SYS\".\"ALL_CONS_COLUMNS\".\"CONSTRAINT_NAME\" = \"SYS\".\"ALL_CONSTRAINTS\".\"CONSTRAINT_NAME\"\n"
                    + ")\n" + "WHERE\n" + "	(\n" + "		\"SYS\".\"ALL_CONSTRAINTS\".\"CONSTRAINT_TYPE\" = 'P'\n"
                    + "		AND \"SYS\".\"ALL_CONSTRAINTS\".\"CONSTRAINT_NAME\" NOT LIKE 'BIN$%'\n"
                    + "		AND UPPER (\n" + "			\"SYS\".\"ALL_CONS_COLUMNS\".\"OWNER\"\n" + "		) IN ('"
                    + SCHEMA + "')\n" + "		AND \"SYS\".\"ALL_CONS_COLUMNS\".\"TABLE_NAME\" = upper('" + tableName
                    + "')\n" + "	)\n" + "ORDER BY\n" + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"OWNER\" ASC,\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"CONSTRAINT_NAME\" ASC,\n"
                    + "	\"SYS\".\"ALL_CONS_COLUMNS\".\"POSITION\" ASC";
            DaoFactory daoFactory = DaoFactory.getInstance();
            DataSet dataSet = daoFactory.queryForDataSet(CONN_NAME, sql);
            while (dataSet.next()) {
                MetaPrimaryKeyInfo meta = new MetaPrimaryKeyInfo();
                meta.setTableName(dataSet.getString("TABLE_NAME").toLowerCase());
                meta.setColumnName(dataSet.getString("COLUMN_NAME").toLowerCase());
                meta.setPropertyName(DaoStringUtils.toClearCase(meta.getColumnName()));
                meta.setKeySeq(0); // 好像没有用到?
                meta.setPkName(dataSet.getString("PK_NAME").toLowerCase());
                list.add(meta);
            }
        } catch (TransactionException e) {
            logger.error(e.getMessage(), e);
        }

        return list;
    }
}
