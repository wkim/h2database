/*
 * Copyright 2004-2008 H2 Group. Multiple-Licensed under the H2 License, 
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.fulltext;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.StringTokenizer;

import org.h2.api.Trigger;
import org.h2.command.Parser;
import org.h2.engine.Session;
import org.h2.expression.Comparison;
import org.h2.expression.ConditionAndOr;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.ValueExpression;
import org.h2.jdbc.JdbcConnection;
import org.h2.tools.SimpleResultSet;
import org.h2.util.ByteUtils;
import org.h2.util.ObjectUtils;
import org.h2.util.JdbcUtils;
import org.h2.util.StringUtils;
import org.h2.value.DataType;

/**
 * This class implements the native full text search.
 */
public class FullText implements Trigger {

    private static final String TRIGGER_PREFIX = "FT_";
    private static final String SCHEMA = "FT";
    
    /**
     * The column name of the result set returned by the search method.
     */
    private static final String FIELD_QUERY = "QUERY";
    
    /**
     * A column name of the result set returned by the searchData method.
     */
    private static final String FIELD_SCHEMA = "SCHEMA";

    /**
     * A column name of the result set returned by the searchData method.
     */
    private static final String FIELD_TABLE = "TABLE";

    /**
     * A column name of the result set returned by the searchData method.
     */
    private static final String FIELD_COLUMNS = "COLUMNS";

    /**
     * A column name of the result set returned by the searchData method.
     */
    private static final String FIELD_KEYS = "KEYS";

    private IndexInfo index;
    private int[] dataTypes;
    private PreparedStatement prepInsertWord, prepInsertRow, prepInsertMap;
    private PreparedStatement prepDeleteRow, prepDeleteMap;
    private PreparedStatement prepSelectRow;

   /**
     * Create a new full text index for a table and column list. Each table may
     * only have one index at any time.
     *
     * @param conn the connection
     * @param schema the schema name of the table
     * @param table the table name
     * @param columnList the column list (null for all columns)
     */
    public static void createIndex(Connection conn, String schema, String table, String columnList) throws SQLException {
        init(conn);
        PreparedStatement prep = conn.prepareStatement("INSERT INTO " + SCHEMA
                + ".INDEXES(SCHEMA, TABLE, COLUMNS) VALUES(?, ?, ?)");
        prep.setString(1, schema);
        prep.setString(2, table);
        prep.setString(3, columnList);
        prep.execute();
        createTrigger(conn, schema, table);
        indexExistingRows(conn, schema, table);
    }

    private static void createTrigger(Connection conn, String schema, String table) throws SQLException {
        Statement stat = conn.createStatement();
        String trigger = StringUtils.quoteIdentifier(schema) + "."
                + StringUtils.quoteIdentifier(TRIGGER_PREFIX + table);
        stat.execute("DROP TRIGGER IF EXISTS " + trigger);
        StringBuffer buff = new StringBuffer("CREATE TRIGGER IF NOT EXISTS ");
        buff.append(trigger);
        buff.append(" AFTER INSERT, UPDATE, DELETE ON ");
        buff.append(StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(table));
        buff.append(" FOR EACH ROW CALL \"");
        buff.append(FullText.class.getName());
        buff.append("\"");
        stat.execute(buff.toString());
    }

    private static void indexExistingRows(Connection conn, String schema, String table) throws SQLException {
        FullText existing = new FullText();
        existing.init(conn, schema, null, table, false, INSERT);
        StringBuffer buff = new StringBuffer("SELECT * FROM ");
        buff.append(StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(table));
        ResultSet rs = conn.createStatement().executeQuery(buff.toString());
        int columnCount = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                row[i] = rs.getObject(i + 1);
            }
            existing.fire(conn, null, row);
        }
    }

    /**
     * Re-creates the full text index for this database
     *
     * @param conn the connection
     */
    public static void reindex(Connection conn) throws SQLException {
        init(conn);
        removeAllTriggers(conn);
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        setting.getWordList().clear();
        Statement stat = conn.createStatement();
        stat.execute("TRUNCATE TABLE " + SCHEMA + ".WORDS");
        stat.execute("TRUNCATE TABLE " + SCHEMA + ".ROWS");
        stat.execute("TRUNCATE TABLE " + SCHEMA + ".MAP");
        ResultSet rs = stat.executeQuery("SELECT * FROM " + SCHEMA + ".INDEXES");
        while (rs.next()) {
            String schema = rs.getString("SCHEMA");
            String table = rs.getString("TABLE");
            createTrigger(conn, schema, table);
            indexExistingRows(conn, schema, table);
        }
    }

    /**
     * Change the ignore list. The ignore list is a comma separated list of
     * common words that must not be indexed. The default ignore list is empty.
     * If indexes already exist at the time this list is changed, reindex must
     * be called.
     * 
     * @param conn the connection
     * @param commaSeparatedList the list
     */
    public static void setIgnoreList(Connection conn, String commaSeparatedList) throws SQLException {
        init(conn);
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        setIgnoreList(setting, commaSeparatedList);
        Statement stat = conn.createStatement();
        stat.execute("TRUNCATE TABLE " + SCHEMA + ".IGNORELIST");
        PreparedStatement prep = conn.prepareStatement("INSERT INTO " + SCHEMA + ".IGNORELIST VALUES(?)");
        prep.setString(1, commaSeparatedList);
        prep.execute();
    }

    private static void setIgnoreList(FullTextSettings setting, String commaSeparatedList) {
        String[] list = StringUtils.arraySplit(commaSeparatedList, ',', true);
        HashSet set = setting.getIgnoreList();
        for (int i = 0; i < list.length; i++) {
            String word = list[i];
            word = setting.convertWord(word);
            if (word != null) {
                set.add(list[i]);
            }
        }
    }

    private static void removeAllTriggers(Connection conn) throws SQLException {
        Statement stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TRIGGERS");
        Statement stat2 = conn.createStatement();
        while (rs.next()) {
            String schema = rs.getString("TRIGGER_SCHEMA");
            String name = rs.getString("TRIGGER_NAME");
            if (name.startsWith(TRIGGER_PREFIX)) {
                name = StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(name);
                stat2.execute("DROP TRIGGER " + name);
            }
        }
    }

    /**
     * Drops all full text indexes from the database.
     *
     * @param conn the connection
     */
    public static void dropAll(Connection conn) throws SQLException {
        init(conn);
        Statement stat = conn.createStatement();
        stat.execute("DROP SCHEMA IF EXISTS " + SCHEMA);
        removeAllTriggers(conn);
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        setting.getIgnoreList().clear();
        setting.getWordList().clear();
    }

    /**
     * Initializes full text search functionality for this database. This adds
     * the following Java functions to the database:
     * <ul>
     * <li>FT_CREATE_INDEX(schemaNameString, tableNameString, columnListString)
     * </li><li>FT_SEARCH(queryString, limitInt, offsetInt): result set 
     * </li><li>FT_REINDEX()
     * </li><li>FT_DROP_ALL()
     * </li></ul>
     * It also adds a schema FULLTEXT to the database where bookkeeping
     * information is stored. This function may be called from a Java
     * application, or by using the SQL statements:
     * <pre>
     * CREATE ALIAS IF NOT EXISTS FULLTEXT_INIT FOR 
     *      &quot;org.h2.fulltext.FullText.init&quot;;
     * CALL FULLTEXT_INIT();
     * </pre>
     * 
     * @param conn the connection
     */
    public static void init(Connection conn) throws SQLException {
        Statement stat = conn.createStatement();
        stat.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        stat.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA
                        + ".INDEXES(ID INT AUTO_INCREMENT PRIMARY KEY, SCHEMA VARCHAR, TABLE VARCHAR, COLUMNS VARCHAR, UNIQUE(SCHEMA, TABLE))");
        stat.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA
                + ".WORDS(ID INT AUTO_INCREMENT PRIMARY KEY, NAME VARCHAR, UNIQUE(NAME))");
        stat.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA
                + ".ROWS(ID IDENTITY, HASH INT, INDEXID INT, KEY VARCHAR, UNIQUE(HASH, INDEXID, KEY))");

        stat.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA
                        + ".MAP(ROWID INT, WORDID INT, PRIMARY KEY(WORDID, ROWID))");

        stat.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".IGNORELIST(LIST VARCHAR)");
        stat.execute("CREATE ALIAS IF NOT EXISTS FT_CREATE_INDEX FOR \"" + FullText.class.getName() + ".createIndex\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FT_SEARCH FOR \"" + FullText.class.getName() + ".search\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FT_SEARCH_DATA FOR \"" + FullText.class.getName() + ".searchData\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FT_REINDEX FOR \"" + FullText.class.getName() + ".reindex\"");
        stat.execute("CREATE ALIAS IF NOT EXISTS FT_DROP_ALL FOR \"" + FullText.class.getName() + ".dropAll\"");
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        ResultSet rs = stat.executeQuery("SELECT * FROM " + SCHEMA + ".IGNORELIST");
        while (rs.next()) {
            String commaSeparatedList = rs.getString(1);
            setIgnoreList(setting, commaSeparatedList);
        }
        rs = stat.executeQuery("SELECT * FROM " + SCHEMA + ".WORDS");
        HashMap map = setting.getWordList();
        while (rs.next()) {
            String word = rs.getString("NAME");
            int id = rs.getInt("ID");
            word = setting.convertWord(word);
            if (word != null) {
                map.put(word, ObjectUtils.getInteger(id));
            }
        }
    }

    /**
     * INTERNAL
     */
    public void init(Connection conn, String schemaName, String triggerName, String tableName, boolean before, int type) throws SQLException {
        init(conn);
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        ArrayList keyList = new ArrayList();
        DatabaseMetaData meta = conn.getMetaData();
        ResultSet rs = meta.getColumns(null, schemaName, tableName, null);
        ArrayList columnList = new ArrayList();
        while (rs.next()) {
            columnList.add(rs.getString("COLUMN_NAME"));
        }
        dataTypes = new int[columnList.size()];
        index = new IndexInfo();
        index.schemaName = schemaName;
        index.tableName = tableName;
        index.columnNames = new String[columnList.size()];
        columnList.toArray(index.columnNames);
        rs = meta.getColumns(null, schemaName, tableName, null);
        for (int i = 0; rs.next(); i++) {
            dataTypes[i] = rs.getInt("DATA_TYPE");
        }
        if (keyList.size() == 0) {
            rs = meta.getPrimaryKeys(null, schemaName, tableName);
            while (rs.next()) {
                keyList.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (keyList.size() == 0) {
            throw new SQLException("No primary key for table " + tableName);
        }
        ArrayList indexList = new ArrayList();
        PreparedStatement prep = conn.prepareStatement(
                "SELECT ID, COLUMNS FROM " + SCHEMA + ".INDEXES WHERE SCHEMA=? AND TABLE=?");
        prep.setString(1, schemaName);
        prep.setString(2, tableName);
        rs = prep.executeQuery();
        if (rs.next()) {
            index.id = rs.getInt(1);
            String columns = rs.getString(2);
            if (columns != null) {
                String[] list = StringUtils.arraySplit(columns, ',', true);
                for (int i = 0; i < list.length; i++) {
                    indexList.add(list[i]);
                }
            }
        }
        if (indexList.size() == 0) {
            indexList.addAll(columnList);
        }
        index.keys = new int[keyList.size()];
        setColumns(index.keys, keyList, columnList);
        index.indexColumns = new int[indexList.size()];
        setColumns(index.indexColumns, indexList, columnList);
        setting.addIndexInfo(index);
        prepInsertWord = conn.prepareStatement(
                "INSERT INTO " + SCHEMA + ".WORDS(NAME) VALUES(?)");
        prepInsertRow = conn.prepareStatement(
                "INSERT INTO " + SCHEMA + ".ROWS(HASH, INDEXID, KEY) VALUES(?, ?, ?)");
        prepInsertMap = conn.prepareStatement(
                "INSERT INTO " + SCHEMA + ".MAP(ROWID, WORDID) VALUES(?, ?)");
        prepDeleteRow = conn.prepareStatement(
                "DELETE FROM " + SCHEMA + ".ROWS WHERE HASH=? AND INDEXID=? AND KEY=?");
        prepDeleteMap = conn.prepareStatement(
                "DELETE FROM " + SCHEMA + ".MAP WHERE ROWID=? AND WORDID=?");
        prepSelectRow = conn.prepareStatement(
                "SELECT ID FROM " + SCHEMA + ".ROWS WHERE HASH=? AND INDEXID=? AND KEY=?");

        PreparedStatement prepSelectMapByWordId = conn.prepareStatement(
                "SELECT ROWID FROM " + SCHEMA + ".MAP WHERE WORDID=?");
        PreparedStatement prepSelectRowById = conn.prepareStatement(
                "SELECT KEY, INDEXID FROM " + SCHEMA + ".ROWS WHERE ID=?");
        setting.setPrepSelectMapByWordId(prepSelectMapByWordId);
        setting.setPrepSelectRowById(prepSelectRowById);
    }

    private void setColumns(int[] index, ArrayList keys, ArrayList columns) throws SQLException {
        for (int i = 0; i < keys.size(); i++) {
            String key = (String) keys.get(i);
            int found = -1;
            for (int j = 0; found == -1 && j < columns.size(); j++) {
                String column = (String) columns.get(j);
                if (column.equals(key)) {
                    found = j;
                }
            }
            if (found < 0) {
                throw new SQLException("FULLTEXT", "Column not found: " + key);
            }
            index[i] = found;
        }
    }

    /**
     * INTERNAL
     */
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        if (oldRow != null) {
            delete(setting, oldRow);
        }
        if (newRow != null) {
            insert(setting, newRow);
        }
    }

    private String getKey(Object[] row) throws SQLException {
        StringBuffer buff = new StringBuffer();
        for (int i = 0; i < index.keys.length; i++) {
            if (i > 0) {
                buff.append(" AND ");
            }
            int columnIndex = index.keys[i];
            buff.append(StringUtils.quoteIdentifier(index.columnNames[columnIndex]));
            Object o = row[columnIndex];
            if (o == null) {
                buff.append(" IS NULL");
            } else {
                buff.append("=");
                buff.append(quoteSQL(o, dataTypes[columnIndex]));
            }
        }
        String key = buff.toString();
        return key;
    }

    private String quoteString(String data) {
        if (data.indexOf('\'') < 0) {
            return "'" + data + "'";
        }
        StringBuffer buff = new StringBuffer(data.length() + 2);
        buff.append('\'');
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == '\'') {
                buff.append(ch);
            }
            buff.append(ch);
        }
        buff.append('\'');
        return buff.toString();
    }

    private String quoteBinary(byte[] data) {
        return "'" + ByteUtils.convertBytesToString(data) + "'";
    }

    private String asString(Object data, int type) throws SQLException {
        if (data == null) {
            return "NULL";
        }
        switch (type) {
        case Types.BIT:
        case DataType.TYPE_BOOLEAN:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.DECIMAL:
        case Types.DOUBLE:
        case Types.FLOAT:
        case Types.NUMERIC:
        case Types.REAL:
        case Types.SMALLINT:
        case Types.TINYINT:
        case Types.DATE:
        case Types.TIME:
        case Types.TIMESTAMP:
        case Types.LONGVARCHAR:
        case Types.CHAR:
        case Types.VARCHAR:
            return data.toString();
        case Types.CLOB:
            int test;
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BINARY:
        case Types.JAVA_OBJECT:
        case Types.OTHER:
        case Types.BLOB:
        case Types.STRUCT:
        case Types.REF:
        case Types.NULL:
        case Types.ARRAY:
        case DataType.TYPE_DATALINK:
        case Types.DISTINCT:
            throw new SQLException("FULLTEXT", "Unsupported column data type: " + type);
        default:
            return "";
        }
    }

    private String quoteSQL(Object data, int type) throws SQLException {
        if (data == null) {
            return "NULL";
        }
        switch (type) {
        case Types.BIT:
        case DataType.TYPE_BOOLEAN:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.DECIMAL:
        case Types.DOUBLE:
        case Types.FLOAT:
        case Types.NUMERIC:
        case Types.REAL:
        case Types.SMALLINT:
        case Types.TINYINT:
            return data.toString();
        case Types.DATE:
        case Types.TIME:
        case Types.TIMESTAMP:
        case Types.LONGVARCHAR:
        case Types.CHAR:
        case Types.VARCHAR:
            return quoteString(data.toString());
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BINARY:
            return quoteBinary((byte[]) data);
        case Types.CLOB:
            int test;
        case Types.JAVA_OBJECT:
        case Types.OTHER:
        case Types.BLOB:
        case Types.STRUCT:
        case Types.REF:
        case Types.NULL:
        case Types.ARRAY:
        case DataType.TYPE_DATALINK:
        case Types.DISTINCT:
            throw new SQLException("FULLTEXT", "Unsupported key data type: " + type);
        default:
            return "";
        }
    }

    private static void addWords(FullTextSettings setting, HashSet set, String text) {
        StringTokenizer tokenizer = new StringTokenizer(text, " \t\n\r\f+\"*%&/()=?'!,.;:-_#@|^~`{}[]");
        while (tokenizer.hasMoreTokens()) {
            String word = tokenizer.nextToken();
            word = setting.convertWord(word);
            if (word != null) {
                set.add(word);
            }
        }
    }

    private int[] getWordIds(FullTextSettings setting, Object[] row) throws SQLException {
        HashSet words = new HashSet();
        for (int i = 0; i < index.indexColumns.length; i++) {
            int idx = index.indexColumns[i];
            String data = asString(row[idx], dataTypes[idx]);
            addWords(setting, words, data);
        }
        HashMap allWords = setting.getWordList();
        int[] wordIds = new int[words.size()];
        Iterator it = words.iterator();
        for (int i = 0; it.hasNext(); i++) {
            String word = (String) it.next();
            Integer wId = (Integer) allWords.get(word);
            int wordId;
            if (wId == null) {
                prepInsertWord.setString(1, word);
                prepInsertWord.execute();
                ResultSet rs = JdbcUtils.getGeneratedKeys(prepInsertWord);
                rs.next();
                wordId = rs.getInt(1);
                allWords.put(word, ObjectUtils.getInteger(wordId));
            } else {
                wordId = wId.intValue();
            }
            wordIds[i] = wordId;
        }
        Arrays.sort(wordIds);
        return wordIds;
    }

    private void insert(FullTextSettings setting, Object[] row) throws SQLException {
        String key = getKey(row);
        int hash = key.hashCode();
        prepInsertRow.setInt(1, hash);
        prepInsertRow.setInt(2, index.id);
        prepInsertRow.setString(3, key);
        prepInsertRow.execute();
        ResultSet rs = JdbcUtils.getGeneratedKeys(prepInsertRow);
        rs.next();
        int rowId = rs.getInt(1);
        prepInsertMap.setInt(1, rowId);
        int[] wordIds = getWordIds(setting, row);
        for (int i = 0; i < wordIds.length; i++) {
            prepInsertMap.setInt(2, wordIds[i]);
            prepInsertMap.execute();
        }
    }

    private void delete(FullTextSettings setting, Object[] row) throws SQLException {
        String key = getKey(row);
        int hash = key.hashCode();
        prepSelectRow.setInt(1, hash);
        prepSelectRow.setInt(2, index.id);
        prepSelectRow.setString(3, key);
        ResultSet rs = prepSelectRow.executeQuery();
        if (rs.next()) {
            int rowId = rs.getInt(1);
            prepDeleteMap.setInt(1, rowId);
            int[] wordIds = getWordIds(setting, row);
            for (int i = 0; i < wordIds.length; i++) {
                prepDeleteMap.setInt(2, wordIds[i]);
                prepDeleteMap.executeUpdate();
            }
            prepDeleteRow.setInt(1, hash);
            prepDeleteRow.setInt(2, index.id);
            prepDeleteRow.setString(3, key);
            prepDeleteRow.executeUpdate();
        }
    }

    /**
     * Searches from the full text index for this database. The result contains
     * the primary key data as an array. The returned result set has the
     * following columns:
     * <ul>
     * <li>SCHEMA (varchar): The schema name. Example: PUBLIC </li>
     * <li>TABLE (varchar): The table name. Example: TEST </li>
     * <li>COLUMNS (array of varchar): Comma separated list of quoted column
     * names. The column names are quoted if necessary. Example: (ID) </li>
     * <li>KEYS (array of values): Comma separated list of values. Example: (1)
     * </li>
     * </ul>
     * 
     * @param conn the connection
     * @param text the search query
     * @param limit the maximum number of rows or 0 for no limit
     * @param offset the offset or 0 for no offset
     * @return the result set
     */
    public static ResultSet searchData(Connection conn, String text, int limit, int offset) throws SQLException {
        return search(conn, text, limit, offset, true);
    }

    /**
     * Searches from the full text index for this database.
     * The returned result set has the following column:
     * <ul><li>QUERY (varchar): The query to use to get the data.
     * The query does not include 'SELECT * FROM '. Example:
     * PUBLIC.TEST WHERE ID = 1
     * </li></ul>
     *
     * @param conn the connection
     * @param text the search query
     * @param limit the maximum number of rows or 0 for no limit
     * @param offset the offset or 0 for no offset
     * @return the result set
     */
    public static ResultSet search(Connection conn, String text, int limit, int offset) throws SQLException {
        return search(conn, text, limit, offset, false);
    }
    
    protected static SimpleResultSet createResultSet(boolean data) throws SQLException {
        SimpleResultSet result = new SimpleResultSet();
        if (data) {
            result.addColumn(FullText.FIELD_SCHEMA, Types.VARCHAR, 0, 0);
            result.addColumn(FullText.FIELD_TABLE, Types.VARCHAR, 0, 0);
            result.addColumn(FullText.FIELD_COLUMNS, Types.ARRAY, 0, 0);
            result.addColumn(FullText.FIELD_KEYS, Types.ARRAY, 0, 0);
        } else {
            result.addColumn(FullText.FIELD_QUERY, Types.VARCHAR, 0, 0);
        }
        return result;
    }

    private static ResultSet search(Connection conn, String text, int limit, int offset, boolean data) throws SQLException {
        SimpleResultSet result = createResultSet(data);
        if (conn.getMetaData().getURL().startsWith("jdbc:columnlist:")) {
            // this is just to query the result set columns
            return result;
        }
        FullTextSettings setting = FullTextSettings.getInstance(conn);
        HashSet words = new HashSet();
        addWords(setting, words, text);
        HashSet rIds = null, lastRowIds = null;
        HashMap allWords = setting.getWordList();

        PreparedStatement prepSelectMapByWordId = setting.getPrepSelectMapByWordId();
        for (Iterator it = words.iterator(); it.hasNext();) {
            lastRowIds = rIds;
            rIds = new HashSet();
            String word = (String) it.next();
            Integer wId = (Integer) allWords.get(word);
            if (wId == null) {
                continue;
            }
            prepSelectMapByWordId.setInt(1, wId.intValue());
            ResultSet rs = prepSelectMapByWordId.executeQuery();
            while (rs.next()) {
                Integer rId = ObjectUtils.getInteger(rs.getInt(1));
                if (lastRowIds == null || lastRowIds.contains(rId)) {
                    rIds.add(rId);
                }
            }
        }
        if (rIds == null || rIds.size() == 0) {
            return result;
        }
        PreparedStatement prepSelectRowById = setting.getPrepSelectRowById();
        int rowCount = 0;
        for (Iterator it = rIds.iterator(); it.hasNext();) {
            int rowId = ((Integer) it.next()).intValue();
            prepSelectRowById.setInt(1, rowId);
            ResultSet rs = prepSelectRowById.executeQuery();
            if (!rs.next()) {
                continue;
            }
            if (offset > 0) {
                offset--;
            } else {
                String key = rs.getString(1);
                int indexId = rs.getInt(2);
                IndexInfo index = setting.getIndexInfo(indexId);
                if (data) {
                    Object[][] columnData = parseKey(conn, key);
                    Object[] row = new Object[] {  
                        index.schemaName,
                        index.tableName,
                        columnData[0],
                        columnData[1]
                    };
                    result.addRow(row);
                } else {
                    StringBuffer buff = new StringBuffer();
                    buff.append(StringUtils.quoteIdentifier(index.schemaName));
                    buff.append('.');
                    buff.append(StringUtils.quoteIdentifier(index.tableName));
                    buff.append(" WHERE ");
                    buff.append(key);
                    String query = buff.toString();
                    result.addRow(new String[] { query });
                }
                rowCount++;
                if (limit > 0 && rowCount >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    protected static Object[][] parseKey(Connection conn, String key) throws SQLException {
        ArrayList columns = new ArrayList();
        ArrayList data = new ArrayList();
        JdbcConnection c = (JdbcConnection) conn;
        Session session = (Session) c.getSession();
        Parser p = new Parser(session);
        Expression expr = p.parseExpression(key);
        addColumnData(columns, data, expr);
        Object[] col = new Object[columns.size()];
        columns.toArray(col);
        Object[] dat = new Object[columns.size()];
        data.toArray(dat);
        Object[][] columnData = new Object[][] {
                col, dat
        };
        return columnData;
    }
    
    private static void addColumnData(ArrayList columns, ArrayList data, Expression expr) {
        if (expr instanceof ConditionAndOr) {
            ConditionAndOr and = (ConditionAndOr) expr;
            Expression left = and.getExpression(true);
            Expression right = and.getExpression(false);
            addColumnData(columns, data, left);
            addColumnData(columns, data, right);
        } else {
            Comparison comp = (Comparison) expr;
            ExpressionColumn ec = (ExpressionColumn) comp.getExpression(true);
            ValueExpression ev = (ValueExpression) comp.getExpression(false);
            String columnName = ec.getColumnName();
            columns.add(columnName);
            if (ev == null) {
                data.add(null);
            } else {
                data.add(ev.getValue(null).getString());
            }
        }
    }

}
