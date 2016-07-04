/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.postgresql.index;

import com.eventsourcing.Entity;
import com.eventsourcing.EntityHandle;
import com.eventsourcing.index.Attribute;
import com.eventsourcing.index.KeyObjectStore;
import com.eventsourcing.layout.Layout;
import com.eventsourcing.layout.TypeHandler;
import com.eventsourcing.postgresql.PostgreSQLSerialization;
import com.eventsourcing.postgresql.PostgreSQLStatementIterator;
import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.TypeResolver;
import com.google.common.io.BaseEncoding;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.support.*;
import com.googlecode.cqengine.index.unique.UniqueIndex;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.query.simple.Equal;
import com.googlecode.cqengine.query.simple.Has;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.resultset.closeable.CloseableResultSet;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PSQLException;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.eventsourcing.postgresql.PostgreSQLSerialization.getParameter;
import static com.eventsourcing.postgresql.PostgreSQLSerialization.setValue;

@Slf4j
public class EqualityIndex<A, O extends Entity> extends AbstractAttributeIndex<A, EntityHandle<O>>
        implements KeyStatisticsAttributeIndex<A, EntityHandle<O>> {

    protected static final int INDEX_RETRIEVAL_COST = 30;
    protected static final int UNIQUE_INDEX_RETRIEVAL_COST = 25;

    private final DataSource dataSource;
    private String tableName;
    private Layout<O> layout;
    private final boolean unique;
    private final TypeHandler attributeTypeHandler;
    private KeyObjectStore<UUID, EntityHandle<O>> keyObjectStore;

    public static <A, O extends Entity> EqualityIndex<A, O> onAttribute(DataSource dataSource,
                                                                        Attribute<O, A> attribute, boolean unique) {
        return new EqualityIndex<>(dataSource, attribute, unique);
    }

    @SneakyThrows
    protected EqualityIndex(DataSource dataSource, Attribute<O, A> attribute, boolean unique) {
        super(attribute, new HashSet<Class<? extends Query>>() {{
            add(Equal.class);
            add(Has.class);
        }});
        this.dataSource = dataSource;
        this.unique = unique;
        layout = Layout.forClass(attribute.getEffectiveObjectType());
        TypeResolver typeResolver = new TypeResolver();
        ResolvedType resolvedType = typeResolver.resolve(attribute.getAttributeType());
        attributeTypeHandler = TypeHandler.lookup(resolvedType, null);
        init();
    }

    @SneakyThrows
    private void init() {
        try(Connection connection = dataSource.getConnection()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(layout.getHash());
            digest.update(attribute.getAttributeName().getBytes());
            String encodedHash = BaseEncoding.base16().encode(digest.digest());
            tableName = "index_" + encodedHash + "_eq";
            if (unique) {
                tableName += "_unique";
            }
            String attributeType = PostgreSQLSerialization.getMappedType(connection, attributeTypeHandler);
            if (unique) {
                attributeType += " UNIQUE";
            }
            String create = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "\"key\" " + attributeType + ",\n" +
                    "\"object\" UUID" +
                    ")";
            try (PreparedStatement s = connection.prepareStatement(create)) {
                s.executeUpdate();
            }
            if (!unique) {
                String indexKey = "CREATE INDEX IF NOT EXISTS " + tableName + "_key_idx ON " + tableName + " (\"key\")";
                try (PreparedStatement s = connection.prepareStatement(indexKey)) {
                    s.executeUpdate();
                }
            }
            String indexObj = "CREATE INDEX IF NOT EXISTS " + tableName + "_obj_idx ON " + tableName + " (\"object\")";
            try (PreparedStatement s = connection.prepareStatement(indexObj)) {
                s.executeUpdate();
            }
            String indexComment = layout.getName() + "." + attribute.getAttributeName() + " EQ";
            if (unique) {
                indexComment += " UNIQUE";
            }
            String comment = "COMMENT ON TABLE " + tableName + " IS '" + indexComment + "'";
            try (PreparedStatement s = connection.prepareStatement(comment)) {
                s.executeUpdate();
            }

        }
    }

    @SneakyThrows
    @Override public CloseableIterable<A> getDistinctKeys(QueryOptions queryOptions) {
        Connection connection = dataSource.getConnection();
        PreparedStatement s = connection.prepareStatement("SELECT DISTINCT key FROM " + tableName + " ORDER BY key");
        return () -> new PostgreSQLStatementIterator<A>(s, connection, true) {
            @Override public A next() {
                return (A) PostgreSQLSerialization.getValue(resultSet, new AtomicInteger(1), attributeTypeHandler);
            }
        };
    }

    @SneakyThrows
    @Override public Integer getCountForKey(A key, QueryOptions queryOptions) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement s = connection.prepareStatement("SELECT COUNT(key) FROM " + tableName + " WHERE " +
                                                                           "key = ?")) {
                setValue(connection, s, 1, key, attributeTypeHandler);
                try (java.sql.ResultSet resultSet = s.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        }
    }

    @SneakyThrows
    @Override public Integer getCountOfDistinctKeys(QueryOptions queryOptions) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement s = connection.prepareStatement("SELECT COUNT(DISTINCT key) FROM " + tableName)) {
                try (java.sql.ResultSet resultSet = s.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        }
    }

    @SneakyThrows
    @Override public CloseableIterable<KeyStatistics<A>> getStatisticsForDistinctKeys(QueryOptions queryOptions) {
        Connection connection = dataSource.getConnection();
        PreparedStatement s = connection.prepareStatement("SELECT DISTINCT key, COUNT(key) FROM " + tableName + " " +
                                                                  "GROUP BY key ORDER BY key");
        return new CloseableIterable<KeyStatistics<A>>() {
            @Override public CloseableIterator<KeyStatistics<A>> iterator() {
                return new PostgreSQLStatementIterator<KeyStatistics<A>>(s, connection, true) {
                    @SneakyThrows
                    @Override public KeyStatistics<A> next() {
                        A key = (A) PostgreSQLSerialization
                                .getValue(resultSet, new AtomicInteger(1), attributeTypeHandler);
                        int count = resultSet.getInt(2);
                        return new KeyStatistics<>(key, count);
                    }
                };
            }
        };
    }

    @SneakyThrows
    @Override public CloseableIterable<KeyValue<A, EntityHandle<O>>> getKeysAndValues(QueryOptions queryOptions) {
        Connection connection = dataSource.getConnection();
        PreparedStatement s = connection.prepareStatement("SELECT key, value FROM " + tableName + " ORDER BY key");
        return new CloseableIterable<KeyValue<A, EntityHandle<O>>>() {
            @Override public CloseableIterator<KeyValue<A, EntityHandle<O>>> iterator() {
                return new PostgreSQLStatementIterator<KeyValue<A, EntityHandle<O>>>(s, connection, true) {
                    @SneakyThrows
                    @Override public KeyValue<A, EntityHandle<O>> next() {
                        AtomicInteger i = new AtomicInteger(1);
                        A key = (A) PostgreSQLSerialization.getValue(resultSet, i, attributeTypeHandler);
                        UUID uuid = UUID.fromString(resultSet.getString(i.get()));
                        return new KeyValueMaterialized<>(key, keyObjectStore.get(uuid));
                    }
                };
            }
        };
    }

    @Override public boolean isMutable() {
        return true;
    }

    @Override public boolean isQuantized() {
        return false;
    }

    @SneakyThrows
    @Override public ResultSet<EntityHandle<O>> retrieve(Query<EntityHandle<O>> query, QueryOptions queryOptions) {
        Class<?> queryClass = query.getClass();
        if (queryClass.equals(Equal.class)) {
            final Equal<EntityHandle<O>, A> equal = (Equal<EntityHandle<O>, A>) query;
            Connection connection = dataSource.getConnection();

            int size = 0;
            try(PreparedStatement counter = connection
                    .prepareStatement("SELECT count(object) FROM " + tableName + " WHERE key = " + getParameter
                            (connection, attributeTypeHandler, null))) {
                setValue(connection, counter, 1, ((Equal<EntityHandle<O>, A>) query).getValue(), attributeTypeHandler);
                try (java.sql.ResultSet resultSet = counter.executeQuery()) {
                    resultSet.next();
                    size = resultSet.getInt(1);
                }
            }

            PreparedStatement s = connection
                    .prepareStatement("SELECT object FROM " + tableName + " WHERE key = " +
                                              getParameter(connection, attributeTypeHandler, null));
            setValue(connection, s, 1, ((Equal<EntityHandle<O>, A>) query).getValue(), attributeTypeHandler);

            PostgreSQLStatementIterator<EntityHandle<O>> iterator = new PostgreSQLStatementIterator<EntityHandle<O>>
                    (s,connection, true) {
                @SneakyThrows
                @Override public EntityHandle<O> next() {
                    UUID uuid = UUID.fromString(resultSet.getString(1));
                    return keyObjectStore.get(uuid);
                }
            };


            int finalSize = size;
            ResultSet<EntityHandle<O>> rs = new ResultSet<EntityHandle<O>>() {
                @Override
                public Iterator<EntityHandle<O>> iterator() {
                    return iterator;
                }

                @Override
                @SneakyThrows
                public boolean contains(EntityHandle<O> object) {
                    try (Connection c = dataSource.getConnection()) {
                        String sql = "SELECT count(key) FROM " + tableName + " WHERE object = ?::UUID";
                        try (PreparedStatement s = c.prepareStatement(sql)) {
                            try (java.sql.ResultSet resultSet = s.executeQuery()) {
                                resultSet.next();
                                return resultSet.getInt(1) > 0;
                            }
                        }
                    }
                }

                @Override
                public boolean matches(EntityHandle<O> object) {
                    return equal.matches(object, queryOptions);
                }

                @Override
                public Query<EntityHandle<O>> getQuery() {
                    return equal;
                }

                @Override
                public QueryOptions getQueryOptions() {
                    return queryOptions;
                }

                @Override
                public int getRetrievalCost() {
                    return unique ? UNIQUE_INDEX_RETRIEVAL_COST : INDEX_RETRIEVAL_COST;
                }

                @Override
                public int getMergeCost() {
                    return finalSize;
                }

                @Override
                public int size() {
                    return finalSize;
                }

                @Override
                public void close() {
                    iterator.close();
                }
            };
            return new CloseableResultSet<>(rs, query, queryOptions);
        } else if (queryClass.equals(Has.class)) {
            final Has<EntityHandle<O>, A> has = (Has<EntityHandle<O>, A>) query;

            Connection connection = dataSource.getConnection();

            int size;
            try(PreparedStatement counter = connection
                    .prepareStatement("SELECT count(object) FROM " + tableName)) {
                try (java.sql.ResultSet resultSet = counter.executeQuery()) {
                    resultSet.next();
                    size = resultSet.getInt(1);
                }
            }

            PreparedStatement s = connection
                    .prepareStatement("SELECT object FROM " + tableName);

            PostgreSQLStatementIterator<EntityHandle<O>> iterator = new PostgreSQLStatementIterator<EntityHandle<O>>(s,connection, true) {
                @SneakyThrows
                @Override public EntityHandle<O> next() {
                    UUID uuid = UUID.fromString(resultSet.getString(1));
                    return keyObjectStore.get(uuid);
                }
            };

            int finalSize = size;
            ResultSet<EntityHandle<O>> rs = new ResultSet<EntityHandle<O>>() {
                @Override
                public Iterator<EntityHandle<O>> iterator() {
                    return iterator;
                }

                @Override
                @SneakyThrows
                public boolean contains(EntityHandle<O> object) {
                    try (Connection c = dataSource.getConnection()) {
                        String sql = "SELECT count(key) FROM " + tableName;
                        try (PreparedStatement s = c.prepareStatement(sql)) {
                            try (java.sql.ResultSet resultSet = s.executeQuery()) {
                                resultSet.next();
                                return resultSet.getInt(1) > 0;
                            }
                        }
                    }
                }

                @Override
                public boolean matches(EntityHandle<O> object) {
                    return has.matches(object, queryOptions);
                }

                @Override
                public Query<EntityHandle<O>> getQuery() {
                    return has;
                }

                @Override
                public QueryOptions getQueryOptions() {
                    return queryOptions;
                }

                @Override
                public int getRetrievalCost() {
                    return unique ? UNIQUE_INDEX_RETRIEVAL_COST : INDEX_RETRIEVAL_COST;
                }

                @Override
                public int getMergeCost() {
                    return finalSize;
                }

                @Override
                public int size() {
                    return finalSize;
                }

                @Override
                public void close() {
                    iterator.close();
                }
            };
            return new CloseableResultSet<>(rs, query, queryOptions);
        } else {
            throw new IllegalArgumentException("Unsupported query: " + query);
        }
    }

    @Override public Index<EntityHandle<O>> getEffectiveIndex() {
        return this;
    }

    @Override public boolean addAll(Collection<EntityHandle<O>> objects, QueryOptions queryOptions) {
        return addAll(objects.iterator(), queryOptions);
    }

    @SneakyThrows
    public boolean addAll(Iterator<EntityHandle<O>> iterator, QueryOptions queryOptions) {
        try(Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            String insert = "INSERT INTO " + tableName + " VALUES (" + getParameter(connection, attributeTypeHandler,
                                                                                    null) + ", ?::UUID)";
            try (PreparedStatement s = connection.prepareStatement(insert)) {
                while (iterator.hasNext()) {
                    EntityHandle<O> object = iterator.next();
                    Iterator<A> attrIterator = attribute.getValues(object, queryOptions).iterator();
                    while (attrIterator.hasNext()) {
                        int i = 1;
                        A attr = attrIterator.next();
                        i = setValue(connection, s, i, attr, attributeTypeHandler);
                        s.setString(i, object.uuid().toString());
                        s.addBatch();
                    }
                }
                if (iterator instanceof CloseableIterable) {
                    ((CloseableIterator<EntityHandle<O>>)iterator).close();
                }

                try {
                    s.executeBatch();
                } catch (BatchUpdateException e) {
                    connection.rollback();
                    SQLException nextException = e.getNextException();
                    if (nextException instanceof PSQLException) {
                        nextException.printStackTrace();
                        if (nextException.getMessage().contains("duplicate key value violates unique constraint")) {
                            throw new UniqueIndex.UniqueConstraintViolatedException(nextException.getMessage());
                        } else {
                            throw e;
                        }
                    } else {
                        throw e;
                    }
                }
            }
            connection.commit();
        }

        return true;
    }

    private void addAll(ObjectStore<EntityHandle<O>> objectStore, QueryOptions queryOptions) {
        addAll(objectStore.iterator(queryOptions), queryOptions);
    }


    @SneakyThrows
    @Override public boolean removeAll(Collection<EntityHandle<O>> objects, QueryOptions queryOptions) {
        try(Connection connection = dataSource.getConnection()) {
            String insert = "DELETE FROM " + tableName + " WHERE object = ?::UUID";
            try (PreparedStatement s = connection.prepareStatement(insert)) {
                Iterator<EntityHandle<O>> iterator = objects.iterator();
                while (iterator.hasNext()) {
                    EntityHandle<O> object = iterator.next();
                    s.setString(1, object.uuid().toString());
                    s.addBatch();
                }
                s.executeBatch();
            }
        }

        return true;
    }

    @SneakyThrows
    @Override public void clear(QueryOptions queryOptions) {
        try(Connection connection = dataSource.getConnection()) {
            try (PreparedStatement s = connection.prepareStatement("DELETE FROM " + tableName)) {
                s.executeUpdate();
            }
        }

    }

    class SetKeyObjectStore implements KeyObjectStore<UUID, EntityHandle<O>> {

        private final ObjectStore<EntityHandle<O>> objectStore;
        private final QueryOptions queryOptions;

        public SetKeyObjectStore(ObjectStore<EntityHandle<O>> objectStore, QueryOptions queryOptions) {
            this.objectStore = objectStore;
            this.queryOptions = queryOptions;
        }

        @Override public EntityHandle<O> get(UUID key) {
            CloseableIterator<EntityHandle<O>> iterator = objectStore.iterator(queryOptions);
            while (iterator.hasNext()) {
                EntityHandle<O> next = iterator.next();
                if (next.uuid().equals(key)) {
                    return next;
                }
            }
            return null;
        }
    }
    @Override public void init(ObjectStore<EntityHandle<O>> objectStore, QueryOptions queryOptions) {
        if (objectStore instanceof KeyObjectStore) {
            this.keyObjectStore = (KeyObjectStore<UUID, EntityHandle<O>>) objectStore;
        } else {
            this.keyObjectStore = new SetKeyObjectStore(objectStore, queryOptions);
        }
        addAll(objectStore, queryOptions);
    }

    @Override public String toString() {
        return "EqualityIndex[PostgreSQL, table=" + tableName + "]";
    }
}
