/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Random;

import org.h2.store.DataHandler;
import org.h2.store.FileStore;
import org.h2.test.TestBase;
import org.h2.util.MemoryUtils;
import org.h2.value.Value;
import org.h2.value.ValueArray;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueByte;
import org.h2.value.ValueBytes;
import org.h2.value.ValueDate;
import org.h2.value.ValueDecimal;
import org.h2.value.ValueDouble;
import org.h2.value.ValueFloat;
import org.h2.value.ValueInt;
import org.h2.value.ValueJavaObject;
import org.h2.value.ValueLob;
import org.h2.value.ValueLong;
import org.h2.value.ValueNull;
import org.h2.value.ValueShort;
import org.h2.value.ValueString;
import org.h2.value.ValueStringFixed;
import org.h2.value.ValueStringIgnoreCase;
import org.h2.value.ValueTime;
import org.h2.value.ValueTimestamp;
import org.h2.value.ValueUuid;

/**
 * Tests the memory consumption of values. Values can estimate how much memory they occupy,
 * and this tests if this estimation is correct.
 */
public class TestValueMemory extends TestBase implements DataHandler {

    private Random random = new Random(1);

    public void test() throws Exception {
        for (int i = 0; i < Value.TYPE_COUNT; i++) {
            testType(i);
        }
    }

    private void testType(int type) throws Exception {
        System.gc();
        System.gc();
        long first = MemoryUtils.getMemoryUsed();
        ArrayList list = new ArrayList();
        long memory = 0;
        for (int i = 0; memory < 1000000; i++) {
            Value v = create(type);
            memory += v.getMemory();
            list.add(v);
        }
        Object[] array = list.toArray();
        IdentityHashMap map = new IdentityHashMap();
        for (int i = 0; i < array.length; i++) {
            map.put(array[i], array[i]);
        }
        int size = map.size();
        map.clear();
        map = null;
        list = null;
        System.gc();
        System.gc();
        long used = MemoryUtils.getMemoryUsed() - first;
        memory /= 1024;
        if (used > memory * 3) {
            error("Type: " + type + " Used memory: " + used + " calculated: " + memory + " " + array.length + " size: " + size);
        }
    }
    Value create(int type) throws SQLException {
        switch (type) {
        case Value.NULL:
            return ValueNull.INSTANCE;
        case Value.BOOLEAN:
            return ValueBoolean.get(false);
        case Value.BYTE:
            return ValueByte.get((byte) random.nextInt());
        case Value.SHORT:
            return ValueShort.get((short) random.nextInt());
        case Value.INT:
            return ValueInt.get(random.nextInt());
        case Value.LONG:
            return ValueLong.get(random.nextLong());
        case Value.DECIMAL:
            return ValueDecimal.get(new BigDecimal(random.nextInt() /*+ "12123344563456345634565234523451312312" */));
        case Value.DOUBLE:
            return ValueDouble.get(random.nextDouble());
        case Value.FLOAT:
            return ValueFloat.get(random.nextFloat());
        case Value.TIME:
            return ValueTime.get(new java.sql.Time(random.nextLong()));
        case Value.DATE:
            return ValueDate.get(new java.sql.Date(random.nextLong()));
        case Value.TIMESTAMP:
            return ValueTimestamp.get(new java.sql.Timestamp(random.nextLong()));
        case Value.BYTES:
            return ValueBytes.get(randomBytes(random.nextInt(1000)));
        case Value.STRING:
            return ValueString.get(randomString(random.nextInt(100)));
        case Value.STRING_IGNORECASE:
            return ValueStringIgnoreCase.get(randomString(random.nextInt(100)));
        case Value.BLOB: {
            int len = (int) Math.abs(random.nextGaussian() * 100);
            byte[] data = randomBytes(len);
            return ValueLob.createBlob(new ByteArrayInputStream(data), len, this);
        }
        case Value.CLOB: {
            int len = (int) Math.abs(random.nextGaussian() * 100);
            String s = randomString(len);
            return ValueLob.createClob(new StringReader(s), len, this);
        }
        case Value.ARRAY: {
            int len = random.nextInt(20);
            Value[] list = new Value[len];
            for (int i = 0; i < list.length; i++) {
                list[i] = create(Value.STRING);
            }
            return ValueArray.get(list);
        }
        case Value.RESULT_SET:
            // not supported currently
            return ValueNull.INSTANCE;
        case Value.JAVA_OBJECT:
            return ValueJavaObject.getNoCopy(randomBytes(random.nextInt(100)));
        case Value.UUID:
            return ValueUuid.get(random.nextLong(), random.nextLong());
        case Value.STRING_FIXED:
            return ValueStringFixed.get(randomString(random.nextInt(100)));
        default:
            throw new Error("type=" + type);
        }
    }

    byte[] randomBytes(int len) {
        byte[] data = new byte[len];
        if (random.nextBoolean()) {
            // don't initialize always (compression)
            random.nextBytes(data);
        }
        return data;
    }

    String randomString(int len) {
        char[] chars = new char[len];
        if (random.nextBoolean()) {
            // don't initialize always (compression)
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) (random.nextGaussian() * 100);
            }
        }
        return new String(chars);
    }

    public int allocateObjectId(boolean needFresh, boolean dataFile) {
        return 0;
    }

    public void checkPowerOff() throws SQLException {
    }

    public void checkWritingAllowed() throws SQLException {
    }

    public int compareTypeSave(Value a, Value b) throws SQLException {
        return 0;
    }

    public String createTempFile() throws SQLException {
        return baseDir + "/valueMemory/data";
//        try {
//            return File.createTempFile("temp", ".tmp", new File(baseDir + "/valueMemory/data")).getAbsolutePath();
//        } catch (IOException e) {
//            throw new SQLException();
//        }
    }

    public void freeUpDiskSpace() throws SQLException {
    }

    public int getChecksum(byte[] data, int start, int end) {
        return 0;
    }

    public String getDatabasePath() {
        return baseDir + "/valueMemory";
    }

    public String getLobCompressionAlgorithm(int type) {
        return "LZF";
    }

    public Object getLobSyncObject() {
        return this;
    }

    public int getMaxLengthInplaceLob() {
        return 100;
    }

    public boolean getTextStorage() {
        return false;
    }

    public void handleInvalidChecksum() throws SQLException {
    }

    public FileStore openFile(String name, String mode, boolean mustExist) throws SQLException {
        return FileStore.open(this, name, mode, null);
    }

}