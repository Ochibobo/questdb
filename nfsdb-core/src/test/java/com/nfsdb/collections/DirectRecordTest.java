package com.nfsdb.collections;

import com.nfsdb.JournalWriter;
import com.nfsdb.column.DirectInputStream;
import com.nfsdb.exceptions.JournalConfigurationException;
import com.nfsdb.exceptions.JournalException;
import com.nfsdb.exceptions.JournalRuntimeException;
import com.nfsdb.factory.configuration.JournalConfigurationBuilder;
import com.nfsdb.lang.cst.JournalRecordSource;
import com.nfsdb.lang.cst.Record;
import com.nfsdb.test.tools.JournalTestFactory;
import com.nfsdb.utils.Files;
import junit.framework.TestCase;
import org.junit.Rule;
import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DirectRecordTest extends TestCase {
    @Rule
    public final JournalTestFactory factory;

    public DirectRecordTest() {
        try {
            this.factory = new JournalTestFactory(
                    new JournalConfigurationBuilder(){{
                    }}.build(Files.makeTempDir())
            );
        } catch (JournalConfigurationException e) {
            throw new JournalRuntimeException(e);
        }
    }

    @Test
    public void testSaveLongField() throws JournalException, IOException {
        writeAndReadRecords(factory.writer(LongValue.class), 100, 450,
                new RecordGenerator<LongValue>(){

                    @Override
                    public LongValue generate(int i) {
                        return new LongValue(i);
                    }

                    @Override
                    public void assertRecord(Record value, int i) {
                        assertEquals((long)i, value.getLong(0));
                    }
                });
    }

    @Test
    public void testSaveNullBinAndStrings() throws JournalException, IOException {
        final int pageLen = 100;
        writeAndReadRecords(factory.writer(StringLongBinary.class), 3, pageLen,
                new RecordGenerator<StringLongBinary>(){

                    @Override
                    public StringLongBinary generate(int i) {
                        StringLongBinary af = new StringLongBinary();
                        af.aLong = i;
                        af.aString = i == 0 ? "A" : null;
                        af.aBinary = i == 1 ?  ByteBuffer.wrap(new byte[2]) : null;
                        return af;
                    }

                    @Override
                    public void assertRecord(Record value, int i) throws IOException {
                        StringLongBinary expected = generate(i);

                        CharSequence str = value.getStr(0);
                        if (expected.aString != null || str != null) {
                            assertEquals(expected.aString, str.toString());
                        }

                        assertEquals(expected.aLong, value.getLong(1));

                        DirectInputStream binCol = value.getBin(2);
                        if (expected.aBinary != null || binCol != null) {
                            byte[] expectedBin = expected.aBinary.array();
                            assertEquals(expectedBin.length, binCol.getLength());
                        }
                    }
                });
    }

    @Test
    public void testSaveBinOverPageEdge() throws JournalException, IOException {
        final int pageLen = 100;
        writeAndReadRecords(factory.writer(Binary.class), 1, pageLen,
                new RecordGenerator<Binary>(){

                    @Override
                    public Binary generate(int i) {
                        Binary af = new Binary();
                        byte[] bin = new byte[pageLen];
                        for(int j = 0; j < bin.length; j++) {
                            bin[j] = (byte)(j % 255);
                        }
                        af.aBinary = ByteBuffer.wrap(bin);
                        return af;
                    }

                    @Override
                    public void assertRecord(Record value, int i) throws IOException {
                        DirectInputStream binCol = value.getBin(0);
                        Binary expected = generate(i);
                        byte[] expectedBin = expected.aBinary.array();
                        assertEquals(expectedBin.length, binCol.getLength());
                        for(int j = 0; j < expectedBin.length; j++) {
                            assertEquals(expectedBin[j], (byte)binCol.read());
                        }
                    }
                });
    }


    @Test
    public void testAllFieldTypesField() throws JournalException, IOException {
        writeAndReadRecords(factory.writer(AllFieldTypes.class), 1000, 64*1024,
                new RecordGenerator<AllFieldTypes>() {

                    @Override
                    public AllFieldTypes generate(int i) {
                        AllFieldTypes af = new AllFieldTypes();
                        byte[] bin = new byte[i];
                        for(int j = 0; j < i; j++) {
                            bin[j] = (byte)(j % 255);
                        }
                        af.aBinary = ByteBuffer.wrap(bin);
                        af.aBool = i%2 == 0 ? true : false;
                        af.aByte = (byte)(i % 255);
                        af.aDouble = i * Math.PI;
                        af.aLong = i * 2;
                        af.anInt = i;
                        af.aShort = (short) (i / 2);
                        StringBuilder sb = new StringBuilder(i);
                        for (int j = 0; j < i; j++) {
                            sb.append((char)j);
                        }
                        af.aString = sb.toString();
                        return af;
                    }

                    @Override
                    public void assertRecord(Record value, int i) throws IOException {
                        AllFieldTypes expected = generate(i);
                        int col = 0;
                        String failedMsg = "Record " + i;
                        assertEquals(failedMsg, expected.aBool, value.getBool(col++));
                        assertEquals(failedMsg,expected.aString, value.getStr(col++).toString());
                        assertEquals(failedMsg,expected.aByte, value.get(col++));
                        assertEquals(failedMsg,expected.aShort, value.getShort(col++));
                        assertEquals(failedMsg,expected.anInt, value.getInt(col++));
                        DirectInputStream binCol = value.getBin(col++);
                        byte[] expectedBin = expected.aBinary.array();
                        assertEquals(failedMsg, expectedBin.length, binCol.getLength());
                        for(int j = 0; j < expectedBin.length; j++) {
                            assertEquals(failedMsg + " byte " + j, expectedBin[j], (byte)binCol.read());
                        }
                        assertEquals(failedMsg, expected.aLong, value.getLong(col++));
                        assertEquals(failedMsg, expected.aDouble, value.getDouble(col++));
                    }
                });
    }

    public <T> void writeAndReadRecords(JournalWriter<T> longJournal, int count, int pageSize, RecordGenerator<T> generator) throws IOException, JournalException {
        for (int i = 0; i < count; i++) {
            longJournal.append(generator.generate(i));
        }

        JournalRecordSource rows = longJournal.rows();
        try (DirectPagedBuffer buffer = new DirectPagedBuffer(pageSize)) {
            DirectRecord dr = new DirectRecord(longJournal.rows().getMetadata(), buffer);
            List<Long> offsets = new ArrayList<>();
            for (Record rec : rows) {
                offsets.add(dr.write(rec));
            }

            for (int i = 0; i < count; i++) {
                Long ost = offsets.get(i);
                dr.init(ost);
                generator.assertRecord(dr, i);
            }
        }
    }

    private static interface RecordGenerator<T> {
        T generate(int i);
        void assertRecord(Record value, int i) throws IOException;
    }

    private static class LongValue {
        long value;

        public LongValue() {
        }

        LongValue(long val) {
            value = val;
        }

        public long getValue() {
            return value;
        }
    }

    private static class AllFieldTypes {
        boolean aBool;
        String aString;
        byte aByte;
        short aShort;
        int anInt;
        ByteBuffer aBinary;
        long aLong;
        double aDouble;
    }

    private static class Binary {
        ByteBuffer aBinary;
    }

    private static class StringLongBinary {
        String aString;
        long aLong;
        ByteBuffer aBinary;
    }
}