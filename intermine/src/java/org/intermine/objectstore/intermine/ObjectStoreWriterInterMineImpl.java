package org.flymine.objectstore.flymine;

/*
 * Copyright (C) 2002-2003 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flymine.metadata.ClassDescriptor;
import org.flymine.metadata.CollectionDescriptor;
import org.flymine.metadata.FieldDescriptor;
import org.flymine.model.FlyMineBusinessObject;
import org.flymine.objectstore.ObjectStore;
import org.flymine.objectstore.ObjectStoreException;
import org.flymine.objectstore.ObjectStoreWriter;
import org.flymine.objectstore.query.Query;
import org.flymine.util.DatabaseUtil;
import org.flymine.util.TypeUtil;
import org.flymine.xml.lite.LiteRenderer;

import org.apache.log4j.Logger;

/**
 * An SQL-backed implementation of the ObjectStoreWriter interface, backed by
 * ObjectStoreFlyMineImpl.
 *
 * @author Matthew Wakeling
 * @author Andrew Varley
 */
public class ObjectStoreWriterFlyMineImpl extends ObjectStoreFlyMineImpl
    implements ObjectStoreWriter
{
    protected static final Logger LOG = Logger.getLogger(ObjectStoreWriterFlyMineImpl.class);
    protected Connection conn = null;
    protected boolean connInUse = false;
    protected ObjectStoreFlyMineImpl os;
    protected int sequenceBase = 0;
    protected int sequenceOffset = SEQUENCE_MULTIPLE;
    protected Statement batch = null;
    protected int batchChars = 0;
    protected String createSituation;

    protected static final int SEQUENCE_MULTIPLE = 100;
    protected static final int MAX_BATCH_CHARS = 20000000;
    /**
     * Constructor for this ObjectStoreWriter. This ObjectStoreWriter is bound to a single SQL
     * Connection, grabbed from the provided ObjectStore.
     *
     * @param os an ObjectStoreFlyMineImpl
     * @throws ObjectStoreException if a problem occurs
     */
    public ObjectStoreWriterFlyMineImpl(ObjectStore os) throws ObjectStoreException {
        super(null, os.getModel());
        this.os = (ObjectStoreFlyMineImpl) os;
        everOptimise = false;
        try {
            conn = this.os.getConnection();
        } catch (SQLException e) {
            throw new ObjectStoreException("Could not obtain connection to database", e);
        }
        this.os.writers.add(this);
        Runtime.getRuntime().addShutdownHook(new StatsShutdownHook(this));
        Exception e = new Exception();
        e.fillInStackTrace();
        StringWriter message = new StringWriter();
        PrintWriter pw = new PrintWriter(message);
        e.printStackTrace(pw);
        pw.close();
        createSituation = message.toString();
        int index = createSituation.indexOf("at junit.framework.TestCase.runBare");
        createSituation = (index < 0 ? createSituation : createSituation.substring(0, index));
    }
    
    /**
     * @see ObjectStoreFlyMineImpl#getConnection
     */
    public Connection getConnection() throws SQLException {
        synchronized (conn) {
            if (conn == null) {
                throw new SQLException("This ObjectStoreWriter is closed");
            }
            while (connInUse) {
                /*Exception trace = new Exception();
                trace.fillInStackTrace();
                StringWriter message = new StringWriter();
                PrintWriter pw = new PrintWriter(message);
                trace.printStackTrace(pw);
                pw.flush();
                LOG.error("Connection in use - entering wait - " + message.toString());*/
                LOG.info("Connection in use - entering wait");
                try {
                    conn.wait(1000L);
                } catch (InterruptedException e) {
                }
                LOG.info("Notified or timed out");
            }
            connInUse = true;
            /*
            Exception trace = new Exception();
            trace.fillInStackTrace();
            StringWriter message = new StringWriter();
            PrintWriter pw = new PrintWriter(message);
            trace.printStackTrace(pw);
            pw.flush();
            LOG.error("getConnection returning connection - " + message.toString());*/
            LOG.info("getConnection returning connection");
            return conn;
        }
    }

    /**
     * @see ObjectStoreFlyMineImpl#releaseConnection
     */
    public void releaseConnection(Connection c) {
        if (c == conn) {
            synchronized (conn) {
                connInUse = false;
                LOG.info("Released connection - notifying");
                conn.notify();
            }
        } else if (c != null) {
            Exception trace = new Exception();
            trace.fillInStackTrace();
            StringWriter message = new StringWriter();
            PrintWriter pw = new PrintWriter(message);
            trace.printStackTrace(pw);
            pw.flush();
            LOG.error("Attempt made to release the wrong connection - " + message.toString());
        }
    }

    /**
     * Overrides Object.finalize - release the connection back to the objectstore.
     */
    public void finalize() {
        if (conn != null) {
            LOG.error("Garbage collecting open ObjectStoreWriterFlyMineImpl with sequence = "
                    + sequence + " createSituation: " + createSituation);
            outputLog();
            close();
        }
    }

    /**
     * @see ObjectStoreWriter#close
     */
    public void close() {
        LOG.error("Close called on ObjectStoreWriterFlyMineImpl with sequence = " + sequence);
        try {
           if (isInTransaction()) {
               abortTransaction();
               LOG.error("ObjectStoreWriterFlyMineImpl closed in unfinished transaction"
                       + " - transaction aborted");
           }
           os.releaseConnection(conn);
           conn = null;
           connInUse = true;
        } catch (Exception e) {
        }
    }

    /**
     * @see ObjectStoreWriter#getObjectStore
     */
    public ObjectStore getObjectStore() {
        return os;
    }

    /**
     * @see ObjectStoreWriter#store
     */
    public void store(FlyMineBusinessObject o) throws ObjectStoreException {
        // TODO:
        // Important: If we are not in a transaction, we still want any store or delete operation
        // to be atomic, even though it uses multiple statements.
        Connection conn = null;
        boolean wasInTransaction = isInTransaction();
        boolean doDeletes = true;
        if (!wasInTransaction) {
            beginTransaction();
        }

        try {
            // Make sure this object has an ID
            if (o.getId() == null) {
                o.setId(getSerial());
                doDeletes = false;
            }

            // Make sure all objects pointed to have IDs
            Map fieldInfos = TypeUtil.getFieldInfos(o.getClass());
            Iterator fieldIter = fieldInfos.entrySet().iterator();
            while (fieldIter.hasNext()) {
                Map.Entry fieldEntry = (Map.Entry) fieldIter.next();
                TypeUtil.FieldInfo fieldInfo = (TypeUtil.FieldInfo) fieldEntry.getValue();
                if (FlyMineBusinessObject.class.isAssignableFrom(fieldInfo.getType())) {
                    FlyMineBusinessObject obj = (FlyMineBusinessObject) TypeUtil.getFieldValue(o,
                            fieldInfo.getName());
                    if ((obj != null) && (obj.getId() == null)) {
                        obj.setId(getSerial());
                    }
                } else if (Collection.class.isAssignableFrom(fieldInfo.getType())) {
                    Collection coll = (Collection) TypeUtil.getFieldValue(o, fieldInfo.getName());
                    Iterator collIter = coll.iterator();
                    while (collIter.hasNext()) {
                        FlyMineBusinessObject obj = (FlyMineBusinessObject) collIter.next();
                        if (obj.getId() == null) {
                            obj.setId(getSerial());
                        }
                    }
                }
            }

            String xml = LiteRenderer.render(o, model);
            Set classDescriptors = model.getClassDescriptorsForClass(o.getClass());

            Statement s = null;
            if (batch != null) {
                s = batch;
            } else {
                conn = getConnection();
                s = conn.createStatement();
                if (wasInTransaction) {
                    batch = s;
                }
            }
            
            Iterator cldIter = classDescriptors.iterator();
            while (cldIter.hasNext()) {
                ClassDescriptor cld = (ClassDescriptor) cldIter.next();
                String tableName = DatabaseUtil.getTableName(cld);
                if (doDeletes) {
                    String toAdd = "DELETE FROM " + tableName + " WHERE id = " + o.getId();
                    addBatch(s, toAdd);
                }
                StringBuffer sql = new StringBuffer("INSERT INTO ")
                    .append(tableName)
                    .append(" (OBJECT");
                fieldIter = cld.getAllFieldDescriptors().iterator();
                while (fieldIter.hasNext()) {
                    FieldDescriptor field = (FieldDescriptor) fieldIter.next();
                    if (!(field instanceof CollectionDescriptor)) {
                        String fieldName = DatabaseUtil.getColumnName(field);
                        sql.append(", ");
                        sql.append(fieldName);
                    }
                }
                sql.append(") VALUES ('")
                    .append(xml)
                    .append("'");
                fieldIter = cld.getAllFieldDescriptors().iterator();
                while (fieldIter.hasNext()) {
                    FieldDescriptor field = (FieldDescriptor) fieldIter.next();
                    if (field instanceof CollectionDescriptor) {
                        CollectionDescriptor collection = (CollectionDescriptor) field;
                        // Collection - if it's many to many, then write indirection table stuff.
                        if (field.relationType() == FieldDescriptor.M_N_RELATION) {
                            String indirectTableName =
                                DatabaseUtil.getIndirectionTableName(collection);
                            String inwardColumnName =
                                DatabaseUtil.getInwardIndirectionColumnName(collection);
                            String outwardColumnName =
                                DatabaseUtil.getOutwardIndirectionColumnName(collection);
                            String leftHandSide = "INSERT INTO " + indirectTableName
                                + " (" + inwardColumnName + ", " + outwardColumnName + ") VALUES ("
                                + o.getId().toString() + ", ";
                            Iterator collIter = ((Collection)
                                    TypeUtil.getFieldValue(o, field.getName())).iterator();
                            while (collIter.hasNext()) {
                                FlyMineBusinessObject inCollection = (FlyMineBusinessObject)
                                    collIter.next();
                                StringBuffer indirectSql = new StringBuffer(leftHandSide);
                                indirectSql.append(inCollection.getId().toString())
                                    .append(");");
                                addBatch(s, indirectSql.toString());
                            }
                        }
                    } else {
                        sql.append(", ");
                        Object value = TypeUtil.getFieldValue(o, field.getName());
                        if (value == null) {
                            sql.append("NULL");
                        } else {
                            SqlGenerator.objectToString(sql, value);
                        }
                    }
                }
                sql.append(");");

                addBatch(s, sql.toString());
            }

            if (batch == null) {
                s.executeBatch();
                logFlushBatch();
                //System//.out.println(getModel().getName()
                //        + ": Executed SQL batch at end of store()");
            }
            invalidateObjectById(o.getId());
        } catch (SQLException e) {
            throw new ObjectStoreException("Error while storing", e);
        } catch (IllegalAccessException e) {
            throw new ObjectStoreException("Illegal access to value while storing", e);
        } finally {
            releaseConnection(conn);
        }

        if (!wasInTransaction) {
            commitTransaction();
        }
    }

    /**
     * Gets an ID number which is unique in the database.
     *
     * @return an Integer
     * @throws SQLException if a problem occurs
     */
    public Integer getSerial() throws SQLException {
        Connection c = null;
        try {
            if (sequenceOffset >= SEQUENCE_MULTIPLE) {
                sequenceOffset = 0;
                c = getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT nextval('serial');");
                //System//.out.println(getModel().getName()
                //        + ": Executed SQL: SELECT nextval('serial');");
                if (!r.next()) {
                    throw new SQLException("No result while attempting to get a unique id");
                }
                long nextSequence = r.getLong(1);
                sequenceBase = (int) (nextSequence * SEQUENCE_MULTIPLE);
            }
            return new Integer(sequenceBase + (sequenceOffset++));
        } finally {
            releaseConnection(c);
        }
    }

    /**
     * @see ObjectStoreWriter#delete
     */
    public void delete(FlyMineBusinessObject o) throws ObjectStoreException {
        // TODO:
        // Important: If we are not in a transaction, we still want any store or delete operation
        // to be atomic, even though it uses multiple statements.
        Connection conn = null;
        boolean wasInTransaction = isInTransaction();
        if (!wasInTransaction) {
            beginTransaction();
        }

        try {
            // Make sure this object has an ID
            if (o.getId() == null) {
                throw new IllegalArgumentException("Attempt to delete an object without an ID: "
                        + o.toString());
            }

            Set classDescriptors = model.getClassDescriptorsForClass(o.getClass());

            Statement s = null;
            if (batch != null) {
                s = batch;
            } else {
                conn = getConnection();
                s = conn.createStatement();
                if (wasInTransaction) {
                    batch = s;
                }
            }
 
            Iterator cldIter = classDescriptors.iterator();
            while (cldIter.hasNext()) {
                ClassDescriptor cld = (ClassDescriptor) cldIter.next();
                String tableName = DatabaseUtil.getTableName(cld);
                addBatch(s, "DELETE FROM " + tableName + " WHERE id = " + o.getId());
            }

            if (batch == null) {
                s.executeBatch();
                logFlushBatch();
                //System//.out.println(getModel().getName()
                //        + ": Executed SQL batch at end of delete()");
            }
            invalidateObjectById(o.getId());
        } catch (SQLException e) {
            throw new ObjectStoreException("Error while deleting", e);
        } finally {
            releaseConnection(conn);
        }

        if (!wasInTransaction) {
            commitTransaction();
        }
    }

    /**
     * @see ObjectStoreWriter#isInTransaction
     */
    public boolean isInTransaction() throws ObjectStoreException {
        Connection c = null;
        try {
            c = getConnection();
            return !c.getAutoCommit();
        } catch (SQLException e) {
            throw new ObjectStoreException("Error finding transaction status", e);
        } finally {
            releaseConnection(c);
        }
    }

    /**
     * @see ObjectStoreWriter#beginTransaction
     */
    public void beginTransaction() throws ObjectStoreException {
        Connection c = null;
        try {
            c = getConnection();
            if (!c.getAutoCommit()) {
                throw new ObjectStoreException("beginTransaction called, but already in"
                        + " transaction");
            }
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new ObjectStoreException("Error beginning transaction", e);
        } finally {
            releaseConnection(c);
        }
    }

    /**
     * @see ObjectStoreWriter#commitTransaction
     */
    public void commitTransaction() throws ObjectStoreException {
        flushBatch();
        Connection c = null;
        try {
            c = getConnection();
            if (c.getAutoCommit()) {
                throw new ObjectStoreException("commitTransaction called, but not in transaction");
            }
            c.commit();
            c.setAutoCommit(true);
            os.flushObjectById();
        } catch (SQLException e) {
            throw new ObjectStoreException("Error committing transaction", e);
        } finally {
            releaseConnection(c);
        }
    }

    /**
     * @see ObjectStoreWriter#abortTransaction
     */
    public void abortTransaction() throws ObjectStoreException {
        flushBatch();
        Connection c = null;
        try {
            c = getConnection();
            if (c.getAutoCommit()) {
                throw new ObjectStoreException("abortTransaction called, but not in transaction");
            }
            c.rollback();
            c.setAutoCommit(true);
            os.flushObjectById();
        } catch (SQLException e) {
            throw new ObjectStoreException("Error aborting transaction", e);
        } finally {
            releaseConnection(c);
        }
    }

    /**
     * @see ObjectStoreFlyMineImpl#execute(Query, int, int, boolean)
     *
     * This method is overridden in order to flush batches properly before the read.
     */
    public List execute(Query q, int start, int limit, boolean optimise, int sequence)
        throws ObjectStoreException {
        flushBatch();
        return super.execute(q, start, limit, optimise, sequence);
    }
    
    /**
     * @see ObjectStoreFlyMineImpl#count
     * 
     * This method is overridden in order to flush batches properly before the read.
     */
    public int count(Query q, int sequence) throws ObjectStoreException {
        flushBatch();
        return super.count(q, sequence);
    }

    /**
     * @see ObjectStoreFlyMineImpl#internalGetObjectById
     *
     * This method is overridden in order to flush matches properly before the read.
     */
    protected FlyMineBusinessObject internalGetObjectById(Integer id) throws ObjectStoreException {
        flushBatch();
        return super.internalGetObjectById(id);
    }

    private void addBatch(Statement s, String toAdd) throws SQLException {
        s.addBatch(toAdd);
        //System//.out.println(getModel().getName() + ": Batched SQL:  " + toAdd);
        logAddBatch();
        batchChars += toAdd.length();
        if (batchChars > MAX_BATCH_CHARS) {
            s.executeBatch();
            logFlushBatch();
            s.clearBatch();
            batchChars = 0;
        }
    }

    private void flushBatch() throws ObjectStoreException {
        if (batch != null) {
            Connection conn = null;
            try {
                conn = getConnection();
                batch.executeBatch();
                logFlushBatch();
                //System//.out.println(getModel().getName()
                //        + ": Executed SQL batch in flushBatch()");
                batch = null;
            } catch (SQLException e) {
                throw new ObjectStoreException("Error while flushing a batch", e);
            } finally {
                releaseConnection(conn);
            }
        }
    }

    private int logOps = 0;
    private int logBatch = 0;
    private boolean emptyBatch = true;

    private synchronized void logAddBatch() {
        logOps++;
        emptyBatch = false;
        if ((logOps % 5000) == 0) {
            outputLog();
        }
    }

    private synchronized void logFlushBatch() {
        logBatch++;
        emptyBatch = true;
    }

    private synchronized void outputLog() {
        int batches = logBatch + (emptyBatch ? 0 : 1);
        if (batches > 0) {
            LOG.error(getModel().getName() + ": Performed " + logOps + " write statements in "
                    + batches + " batches. Average batch size: " + (logOps / batches));
        }
    }

    /**
     * Called by the StatsShutdownHook on shutdown
     */
    public void shutdown() {
        if (conn != null) {
            LOG.error("Shutting down open ObjectStoreWriterFlyMineImpl with sequence = "
                    + sequence + ", createSituation = " + createSituation);
            outputLog();
            close();
        }
    }

    /**
     * @see ObjectStore#isMultiConnection
     */
    public boolean isMultiConnection() {
        return false;
    }
}
