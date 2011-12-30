/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0
 * (license2)
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;

import org.h2.test.TestBase;
import org.h2.util.IOUtils;

/**
 * Tests the stream to UTF-8 reader conversion.
 */
public class TestReader extends TestBase {

    public void test() throws Exception {
        String s = "\u00ef\u00f6\u00fc";
        StringReader r = new StringReader(s);
        InputStream in = IOUtils.getInputStream(r);
        byte[] buff = IOUtils.readBytesAndClose(in, 0);
        InputStream in2 = new ByteArrayInputStream(buff);
        Reader r2 = IOUtils.getReader(in2);
        String s2 = IOUtils.readStringAndClose(r2, Integer.MAX_VALUE);
        check(s2, "\u00ef\u00f6\u00fc");
    }

}