/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0
 * (license2)
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.io.Serializable;

/**
 * A utility class for TestLob.
 */
class TestLobObject implements Serializable {
    private static final long serialVersionUID = 904356179316518715L;
    String data;

    TestLobObject(String data) {
        this.data = data;
    }
}