/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import java.nio.ByteBuffer
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import javax.sql.rowset.serial.SerialClob
import javax.sql.rowset.serial.SerialBlob

import org.moqui.entity.EntityException
import org.moqui.impl.StupidUtilities
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEParameterSpec
import javax.crypto.spec.PBEKeySpec
import org.apache.commons.codec.binary.Hex

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class EntityQueryBuilder {
    protected final static Logger logger = LoggerFactory.getLogger(EntityQueryBuilder.class)

    protected EntityFacadeImpl efi
    protected EntityDefinition mainEntityDefinition
    protected StringBuilder sqlTopLevel = new StringBuilder()
    protected List<EntityConditionParameter> parameters = new ArrayList()

    protected PreparedStatement ps
    protected ResultSet rs
    protected Connection connection
    protected boolean externalConnection = false

    EntityQueryBuilder(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.mainEntityDefinition = entityDefinition
        this.efi = efi
    }

    EntityDefinition getMainEd() { return mainEntityDefinition }

    /** @return StringBuilder meant to be appended to */
    StringBuilder getSqlTopLevel() {
        return this.sqlTopLevel
    }

    /** returns List of EntityConditionParameter meant to be added to */
    List<EntityConditionParameter> getParameters() {
        return this.parameters
    }

    Connection makeConnection() {
        this.connection = this.efi.getConnection(this.efi.getEntityGroupName(mainEntityDefinition))
        return this.connection
    }

    void useConnection(Connection c) { this.connection = c; externalConnection = true }

    protected static void handleSqlException(Exception e, String sql) {
        throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e)
    }

    PreparedStatement makePreparedStatement() {
        if (!this.connection) throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place")
        String sql = this.getSqlTopLevel().toString()
        try {
            this.ps = connection.prepareStatement(sql)
        } catch (SQLException sqle) {
            handleSqlException(sqle, sql)
        }
        return this.ps
    }

    ResultSet executeQuery() throws EntityException {
        if (!this.ps) throw new IllegalStateException("Cannot Execute Query, no PreparedStatement in place")
        try {
            long timeBefore = logger.isTraceEnabled() ? System.currentTimeMillis() : 0
            this.rs = this.ps.executeQuery()
            if (logger.isTraceEnabled()) logger.trace("Executed query with SQL [${getSqlTopLevel().toString()}] and parameters [${parameters}] in [${(System.currentTimeMillis()-timeBefore)/1000}] seconds")
            return this.rs
        } catch (SQLException sqle) {
            throw new EntityException("Error in query for:" + this.sqlTopLevel, sqle)
        }
    }

    public int executeUpdate() throws EntityException {
        if (!this.ps) throw new IllegalStateException("Cannot Execute Update, no PreparedStatement in place")
        try {
            long timeBefore = logger.isTraceEnabled() ? System.currentTimeMillis() : 0
            int rows = ps.executeUpdate()
            if (logger.isTraceEnabled()) logger.trace("Executed update with SQL [${getSqlTopLevel().toString()}] and parameters [${parameters}] in [${(System.currentTimeMillis()-timeBefore)/1000}] seconds changing [${rows}] rows")
            return rows
        } catch (SQLException sqle) {
            throw new EntityException("Error in update for:" + this.sqlTopLevel, sqle)
        }
    }

    /** NOTE: this should be called in a finally clause to make sure things are closed */
    void closeAll() {
        if (this.ps != null) {
            this.ps.close()
            this.ps = null
        }
        if (this.rs != null) {
            this.rs.close()
            this.rs = null
        }
        if (this.connection != null && !externalConnection) {
            this.connection.close()
            this.connection = null
        }
    }

    /** Only close the PreparedStatement, leave the ResultSet and Connection open, but null references to them
     * NOTE: this should be called in a finally clause to make sure things are closed
     */
    void releaseAll() {
        this.ps = null
        this.rs = null
        this.connection = null
    }

    static String sanitizeColumnName(String colName) {
        String interim = colName.replace('.', '_').replace('(','_').replace(')','_').replace('+','_').replace(' ','')
        while (interim.charAt(0) == '_') interim = interim.substring(1)
        while (interim.charAt(interim.length()-1) == '_') interim = interim.substring(0, interim.length()-1)
        while (interim.contains('__')) interim = interim.replace('__', '_')
        return interim
    }

    void getResultSetValue(int index, Node fieldNode, EntityValueImpl entityValueImpl) throws EntityException {
        getResultSetValue(this.rs, index, fieldNode, entityValueImpl, this.efi)
    }

    void setPreparedStatementValue(int index, Object value, Node fieldNode) throws EntityException {
        setPreparedStatementValue(this.ps, index, value, fieldNode, this.mainEntityDefinition, this.efi)
    }

    void setPreparedStatementValues() {
        // set all of the values from the SQL building in efb
        int paramIndex = 1
        for (EntityConditionParameter entityConditionParam: getParameters()) {
            entityConditionParam.setPreparedStatementValue(paramIndex)
            paramIndex++
        }
    }

    static class EntityConditionParameter {
        protected final static Logger logger = LoggerFactory.getLogger(EntityConditionParameter.class)

        protected Node fieldNode
        protected Object value
        protected EntityQueryBuilder eqb

        EntityConditionParameter(Node fieldNode, Object value, EntityQueryBuilder eqb) {
            this.fieldNode = fieldNode
            this.value = value
            this.eqb = eqb
        }

        Node getFieldNode() { return this.fieldNode }

        Object getValue() { return this.value }

        void setPreparedStatementValue(int index) throws EntityException {
            setPreparedStatementValue(this.eqb.ps, index, this.value, this.fieldNode,
                    this.eqb.mainEntityDefinition, this.eqb.efi)
        }

        @Override
        String toString() { return fieldNode."@name" + ":" + value }
    }

    static void getResultSetValue(ResultSet rs, int index, Node fieldNode, EntityValueImpl entityValueImpl,
                                            EntityFacadeImpl efi) throws EntityException {
        String fieldName = fieldNode."@name"
        String javaType = efi.getFieldJavaType((String) fieldNode."@type", entityValueImpl.getEntityDefinition())
        int typeValue = EntityFacadeImpl.getJavaTypeInt(javaType)

        Object value = null
        try {
            ResultSetMetaData rsmd = rs.getMetaData()
            int colType = rsmd.getColumnType(index)

            switch (typeValue) {
            case 1:
                if (Types.CLOB == colType) {
                    // if the String is empty, try to get a text input stream, this is required for some databases
                    // for larger fields, like CLOBs
                    Clob valueClob = rs.getClob(index)
                    Reader valueReader = null
                    if (valueClob != null) {
                        valueReader = valueClob.getCharacterStream()
                    }

                    if (valueReader != null) {
                        // read up to 4096 at a time
                        char[] inCharBuffer = new char[4096]
                        StringBuilder strBuf = new StringBuilder()
                        try {
                            int charsRead
                            while ((charsRead = valueReader.read(inCharBuffer, 0, 4096)) > 0) {
                                strBuf.append(inCharBuffer, 0, charsRead)
                            }
                            valueReader.close()
                        } catch (IOException e) {
                            throw new EntityException("Error reading long character stream for field [${fieldName}] of entity [${entityValueImpl.getEntityName()}]", e)
                        }
                        value = strBuf.toString()
                    }
                } else {
                    value = rs.getString(index)
                }
                break
            case 2:
                try {
                    value = rs.getTimestamp(index, (Calendar) efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly())
                } catch (SQLException e) {
                    if (logger.isTraceEnabled()) logger.trace("Ignoring SQLException for getTimestamp(), leaving null (found this in MySQL with a date/time value of [0000-00-00 00:00:00]): ${e.toString()}")
                }
                break
            case 3: value = rs.getTime(index, (Calendar) efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()); break
            case 4: value = rs.getDate(index, (Calendar) efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()); break
            case 5: int intValue = rs.getInt(index); if (!rs.wasNull()) value = intValue; break
            case 6: long longValue = rs.getLong(index); if (!rs.wasNull()) value = longValue; break
            case 7: float floatValue = rs.getFloat(index); if (!rs.wasNull()) value = floatValue; break
            case 8: double doubleValue = rs.getDouble(index); if (!rs.wasNull()) value = doubleValue; break
            case 9: BigDecimal bigDecimalValue = rs.getBigDecimal(index); if (!rs.wasNull()) value = bigDecimalValue?.stripTrailingZeros(); break
            case 10: boolean booleanValue = rs.getBoolean(index); if (!rs.wasNull()) value = Boolean.valueOf(booleanValue); break
            case 11:
                Object obj = null
                byte[] originalBytes = rs.getBytes(index)
                InputStream binaryInput = null;
                if (originalBytes != null && originalBytes.length > 0) {
                    binaryInput = new ByteArrayInputStream(originalBytes);
                }
                if (originalBytes != null && originalBytes.length <= 0) {
                    logger.warn("Got byte array back empty for serialized Object with length [${originalBytes.length}] for field [${fieldName}] (${index})")
                }
                if (binaryInput != null) {
                    ObjectInputStream inStream = null
                    try {
                        inStream = new ObjectInputStream(binaryInput)
                        obj = inStream.readObject()
                    } catch (IOException ex) {
                        if (logger.traceEnabled) logger.trace("Unable to read BLOB from input stream for field [${fieldName}] (${index}): ${ex.toString()}")
                    } catch (ClassNotFoundException ex) {
                        if (logger.traceEnabled) logger.trace("Class not found: Unable to cast BLOB data to an Java object for field [${fieldName}] (${index}); most likely because it is a straight byte[], so just using the raw bytes: ${ex.toString()}")
                    } finally {
                        if (inStream != null) {
                            try {
                                inStream.close()
                            } catch (IOException e) {
                                throw new EntityException("Unable to close binary input stream for field [${fieldName}] (${index}): ${e.toString()}", e)
                            }
                        }
                    }
                }
                if (obj != null) {
                    value = obj
                } else {
                    value = originalBytes
                }
                break
            case 12:
                SerialBlob sblob = null
                try {
                    // NOTE: changed to try getBytes first because Derby blows up on getBlob and on then calling getBytes for the same field, complains about getting value twice
                    byte[] fieldBytes = rs.getBytes(index)
                    if (!rs.wasNull()) sblob = new SerialBlob(fieldBytes)
                    // fieldBytes = theBlob != null ? theBlob.getBytes(1, (int) theBlob.length()) : null
                } catch (SQLException e) {
                    if (logger.isTraceEnabled()) logger.trace("Ignoring exception trying getBytes(), trying getBlob(): ${e.toString()}")
                    Blob theBlob = rs.getBlob(index)
                    if (!rs.wasNull()) sblob = new SerialBlob(theBlob)
                }
                value = sblob
                break
            case 13: value = new SerialClob(rs.getClob(index)); break
            case 14:
            case 15: value = rs.getObject(index); break
            }
        } catch (SQLException sqle) {
            logger.error("SQL Exception while getting value for field: [${fieldName}] (${index})", sqle)
            throw new EntityException("SQL Exception while getting value for field: [${fieldName}] (${index})", sqle)
        }

        // if field is to be encrypted, do it now
        if (value && fieldNode."@encrypt" == "true") {
            if (typeValue != 1) throw new IllegalArgumentException("The encrypt attribute was set to true on non-String field [${fieldName}] of entity [${entityValueImpl.getEntityName()}]")
            String original = value.toString()
            try {
                value = enDeCrypt(original, false, efi)
            } catch (Exception e) {
                logger.error("Error decrypting field [${fieldName}] of entity [${entityValueImpl.getEntityName()}]", e)
            }
        }

        entityValueImpl.getValueMap().put(fieldName, value)
    }

    public static String enDeCrypt(String value, boolean encrypt, EntityFacadeImpl efi) {
        Node entityFacadeNode = efi.ecfi.confXmlRoot."entity-facade"[0]
        String pwStr = entityFacadeNode."@crypt-pass"
        if (!pwStr) throw new IllegalArgumentException("No entity-facade.@crypt-pass setting found, NOT doing encryption")

        byte[] salt = (entityFacadeNode."@crypt-salt" ?: "default1").getBytes()
        if (salt.length > 8) salt = salt[0..7]
        while (salt.length < 8) salt += (byte)0x45
        int count = (entityFacadeNode."@crypt-iter" as Integer) ?: 10
        char[] pass = pwStr.toCharArray()

        String algo = entityFacadeNode."@crypt-algo" ?: "PBEWithMD5AndDES"

        // logger.info("TOREMOVE salt [${salt}] count [${count}] pass [${pass}] algo [${algo}]")
        PBEParameterSpec pbeParamSpec = new PBEParameterSpec(salt, count)
        PBEKeySpec pbeKeySpec = new PBEKeySpec(pass)
        SecretKeyFactory keyFac = SecretKeyFactory.getInstance(algo)
        SecretKey pbeKey = keyFac.generateSecret(pbeKeySpec)

        Cipher pbeCipher = Cipher.getInstance(algo)
        pbeCipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, pbeKey, pbeParamSpec)

        byte[] inBytes
        if (encrypt) {
            inBytes = value.getBytes()
        } else {
            inBytes = Hex.decodeHex(value.toCharArray())
        }
        byte[] outBytes = pbeCipher.doFinal(inBytes)
        return encrypt ? Hex.encodeHexString(outBytes) : new String(outBytes)
    }

    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, Node fieldNode,
                                          EntityDefinition ed, EntityFacadeImpl efi) throws EntityException {
        String entityName = ed.getFullEntityName()
        String fieldName = fieldNode."@name"
        String javaType = efi.getFieldJavaType((String) fieldNode."@type", ed)
        int typeValue = EntityFacadeImpl.getJavaTypeInt(javaType)
        if (value != null) {
            if (!StupidUtilities.isInstanceOf(value, javaType)) {
                // this is only an info level message because under normal operation for most JDBC
                // drivers this will be okay, but if not then the JDBC driver will throw an exception
                // and when lower debug levels are on this should help give more info on what happened
                String fieldClassName = value.getClass().getName()
                if (value instanceof byte[]) {
                    fieldClassName = "byte[]"
                }

                if (logger.traceEnabled) logger.trace("Type of field " + entityName + "." + fieldNode."@name" +
                        " is " + fieldClassName + ", was expecting " + javaType + " this may " +
                        "indicate an error in the configuration or in the class, and may result " +
                        "in an SQL-Java data conversion error. Will use the real field type: " +
                        fieldClassName + ", not the definition.")
                javaType = fieldClassName
                typeValue = EntityFacadeImpl.getJavaTypeInt(javaType)
            }

            // if field is to be encrypted, do it now
            if (fieldNode."@encrypt" == "true") {
                if (typeValue != 1) throw new IllegalArgumentException("The encrypt attribute was set to true on non-String field [${fieldName}] of entity [${entityName}]")
                String original = value.toString()
                value = enDeCrypt(original, true, efi)
            }
        }

        boolean useBinaryTypeForBlob = ("true" == efi.getDatabaseNode(efi.getEntityGroupName(ed))."@use-binary-type-for-blob")
        try {
            setPreparedStatementValue(ps, index, value, typeValue, useBinaryTypeForBlob, efi)
        } catch (EntityException e) {
            throw e
        } catch (Exception e) {
            throw new EntityException("Error setting prepared statement field [${fieldName}] of entity [${entityName}]", e)
        }
    }

    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, EntityDefinition ed,
                                          EntityFacadeImpl efi) throws EntityException {
        boolean useBinaryTypeForBlob = ("true" == efi.getDatabaseNode(efi.getEntityGroupName(ed))."@use-binary-type-for-blob")
        int typeValue = value ? EntityFacadeImpl.getJavaTypeInt(value.class.name) : 1
        setPreparedStatementValue(ps, index, value, typeValue, useBinaryTypeForBlob, efi)

    }

    /* This is called by the other two setPreparedStatementValue methods */
    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, int typeValue,
                                          boolean useBinaryTypeForBlob, EntityFacadeImpl efi) throws EntityException {
        try {
            // allow setting, and searching for, String values for all types; JDBC driver should handle this okay
            if (value instanceof String) {
                ps.setString(index, value)
            } else {
                switch (typeValue) {
                case 1: if (value != null) { ps.setString(index, value as String) } else { ps.setNull(index, Types.VARCHAR) }; break
                case 2: if (value != null) { ps.setTimestamp(index, value as Timestamp, (Calendar) efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()) }
                else { ps.setNull(index, Types.TIMESTAMP) }; break
                case 3:
                    Time tm = value as Time
                    // logger.warn("=================== setting time tm=${tm} tm long=${tm.getTime()}, cal=${cal}")
                    if (value != null) { ps.setTime(index, tm, (Calendar) efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()) }
                    else { ps.setNull(index, Types.TIME) }
                    break
                case 4:
                    java.sql.Date dt = (java.sql.Date) value
                    // logger.warn("=================== setting date dt=${dt} dt long=${dt.getTime()}, cal=${cal}")
                    if (value != null) { ps.setDate(index, dt, (Calendar) efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()) }
                    else { ps.setNull(index, Types.DATE) }
                    break
                case 5: if (value != null) { ps.setInt(index, (Integer) value) } else { ps.setNull(index, Types.NUMERIC) }; break
                case 6: if (value != null) { ps.setLong(index, (Long) value) } else { ps.setNull(index, Types.NUMERIC) }; break
                case 7: if (value != null) { ps.setFloat(index, (Float) value) } else { ps.setNull(index, Types.NUMERIC) }; break
                case 8: if (value != null) { ps.setDouble(index, (Double) value) } else { ps.setNull(index, Types.NUMERIC) }; break
                case 9: if (value != null) { ps.setBigDecimal(index, (BigDecimal) value) } else { ps.setNull(index, Types.NUMERIC) }; break
                case 10: if (value != null) { ps.setBoolean(index, (Boolean) value) } else { ps.setNull(index, Types.BOOLEAN) }; break
                case 11:
                    if (value != null) {
                        try {
                            ByteArrayOutputStream os = new ByteArrayOutputStream()
                            ObjectOutputStream oos = new ObjectOutputStream(os)
                            oos.writeObject(value)
                            oos.close()
                            byte[] buf = os.toByteArray()
                            os.close()

                            ByteArrayInputStream is = new ByteArrayInputStream(buf)
                            ps.setBinaryStream(index, is, buf.length)
                            is.close()
                        } catch (IOException ex) {
                            throw new EntityException("Error setting serialized object", ex)
                        }
                    } else {
                        if (useBinaryTypeForBlob) { ps.setNull(index, Types.BINARY) } else { ps.setNull(index, Types.BLOB) }
                    }
                    break
                case 12:
                    if (value instanceof byte[]) {
                        ps.setBytes(index, (byte[]) value)
                    } else if (value instanceof ArrayList) {
                        byte[] theBytes = new byte[((ArrayList) value).size()]
                        ((ArrayList) value).toArray(theBytes)
                        ps.setBytes(index, theBytes)
                    } else if (value instanceof ByteBuffer) {
                        ps.setBytes(index, ((ByteBuffer) value).array())
                    } else if (value instanceof String) {
                        ps.setBytes(index, ((String) value).getBytes())
                    } else {
                        if (value != null) {
                            Blob blob = (Blob) value
                            ps.setBytes(index, blob.getBytes(1, (int) blob.length()))
                        } else {
                            if (useBinaryTypeForBlob) { ps.setNull(index, Types.BINARY) } else { ps.setNull(index, Types.BLOB) }
                        }
                    }
                    break
                case 13: if (value != null) { ps.setClob(index, (Clob) value) } else { ps.setNull(index, Types.CLOB) }; break
                case 14: if (value != null) { ps.setTimestamp(index, value as Timestamp) } else { ps.setNull(index, Types.TIMESTAMP) }; break
                // TODO: is this the best way to do collections and such?
                case 15: if (value != null) { ps.setObject(index, value, Types.JAVA_OBJECT) } else { ps.setNull(index, Types.JAVA_OBJECT) }; break
                }
            }
        } catch (SQLException sqle) {
            throw new EntityException("SQL Exception while setting value: " + sqle.toString(), sqle)
        } catch (Exception e) {
            throw new EntityException("Error while setting value: " + e.toString(), e)
        }
    }
}
