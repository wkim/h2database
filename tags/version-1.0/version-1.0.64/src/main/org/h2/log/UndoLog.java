/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.log;

import java.sql.SQLException;

import org.h2.constant.SysProperties;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.message.Message;
import org.h2.store.DataPage;
import org.h2.store.FileStore;
import org.h2.util.ObjectArray;

/**
 * Each session keeps a undo log if rollback is required.
 */
public class UndoLog {
    private Session session;
    private Database database;
    // TODO undo log entry: a chain would probably be faster & use less memory than an array
    private ObjectArray records = new ObjectArray();
    private FileStore file;
    private DataPage rowBuff;
    private int memoryUndo;

    public UndoLog(Session session) {
        this.session = session;
        this.database = session.getDatabase();
    }

    public int size() {
        if (SysProperties.CHECK && memoryUndo > records.size()) {
            throw Message.getInternalError();
        }
        return records.size();
    }

    public void clear() {
        records.clear();
        memoryUndo = 0;
        if (file != null) {
            file.closeAndDeleteSilently();
            file = null;
            rowBuff = null;
        }
    }

    public UndoLogRecord getAndRemoveLast() throws SQLException {
        int i = records.size() - 1;
        UndoLogRecord entry = (UndoLogRecord) records.get(i);
        if (entry.isStored()) {
            int start = Math.max(0, i - database.getMaxMemoryUndo() / 2);
            UndoLogRecord first = null;
            for (int j = start; j <= i; j++) {
                UndoLogRecord e = (UndoLogRecord) records.get(j);
                if (e.isStored()) {
                    e.load(rowBuff, file, session);
                    memoryUndo++;
                    if (first == null) {
                        first = e;
                    }
                }
            }
            first.seek(file);
        }
        records.remove(i);
        memoryUndo--;
        return entry;
    }

    public void add(UndoLogRecord entry) throws SQLException {
        records.add(entry);
        memoryUndo++;
        if (memoryUndo > database.getMaxMemoryUndo() && database.isPersistent()) {
            if (file == null) {
                String fileName = database.createTempFile();
                file = database.openFile(fileName, "rw", false);
                file.autoDelete();
                file.seek(FileStore.HEADER_LENGTH);
                rowBuff = DataPage.create(database, Constants.DEFAULT_DATA_PAGE_SIZE);
                DataPage buff = rowBuff;
                for (int i = 0; i < records.size(); i++) {
                    UndoLogRecord r = (UndoLogRecord) records.get(i);
                    saveIfPossible(r, buff);
                }
            } else {
                saveIfPossible(entry, rowBuff);
            }
        }
    }

    private void saveIfPossible(UndoLogRecord r, DataPage buff) throws SQLException {
        if (!r.isStored() && r.canStore()) {
            r.save(buff, file);
            memoryUndo--;
        }
    }

}