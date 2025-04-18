package com.netease.nim.camellia.redis.jediscluster;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.JedisURIHelper;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import java.net.URI;


/**
 * Created by caojiajun on 2024/11/11
 */
public class ReadOnlyJedisFactory implements PooledObjectFactory<Jedis> {

    private static final Logger logger = LoggerFactory.getLogger(JedisFactory.class);

    private final JedisSocketFactory jedisSocketFactory;

    private final JedisClientConfig clientConfig;

    protected ReadOnlyJedisFactory(final String host, final int port, final int connectionTimeout,
                           final int soTimeout, final String password, final int database, final String clientName) {
        this(host, port, connectionTimeout, soTimeout, password, database, clientName, false, null, null, null);
    }

    protected ReadOnlyJedisFactory(final String host, final int port, final int connectionTimeout,
                           final int soTimeout, final String user, final String password, final int database, final String clientName) {
        this(host, port, connectionTimeout, soTimeout, 0, user, password, database, clientName);
    }

    protected ReadOnlyJedisFactory(final String host, final int port, final int connectionTimeout, final int soTimeout,
                           final int infiniteSoTimeout, final String user, final String password, final int database, final String clientName) {
        this(host, port, connectionTimeout, soTimeout, infiniteSoTimeout, user, password, database, clientName, false, null, null, null);
    }

    /**
     * {@link #setHostAndPort(redis.clients.jedis.HostAndPort) setHostAndPort} must be called later.
     */
    protected ReadOnlyJedisFactory(final int connectionTimeout, final int soTimeout, final int infiniteSoTimeout,
                           final String user, final String password, final int database, final String clientName) {
        this(connectionTimeout, soTimeout, infiniteSoTimeout, user, password, database, clientName, false, null, null, null);
    }

    protected ReadOnlyJedisFactory(final String host, final int port, final int connectionTimeout,
                           final int soTimeout, final String password, final int database, final String clientName,
                           final boolean ssl, final SSLSocketFactory sslSocketFactory, final SSLParameters sslParameters,
                           final HostnameVerifier hostnameVerifier) {
        this(host, port, connectionTimeout, soTimeout, null, password, database, clientName, ssl, sslSocketFactory, sslParameters, hostnameVerifier);
    }

    protected ReadOnlyJedisFactory(final String host, final int port, final int connectionTimeout,
                           final int soTimeout, final String user, final String password, final int database, final String clientName,
                           final boolean ssl, final SSLSocketFactory sslSocketFactory, final SSLParameters sslParameters,
                           final HostnameVerifier hostnameVerifier) {
        this(host, port, connectionTimeout, soTimeout, 0, user, password, database, clientName, ssl, sslSocketFactory, sslParameters, hostnameVerifier);
    }

    protected ReadOnlyJedisFactory(final HostAndPort hostAndPort, final JedisClientConfig clientConfig) {
        this.clientConfig = DefaultJedisClientConfig.copyConfig(clientConfig);
        this.jedisSocketFactory = new DefaultJedisSocketFactory(hostAndPort, this.clientConfig);
    }

    protected ReadOnlyJedisFactory(final String host, final int port, final int connectionTimeout, final int soTimeout,
                           final int infiniteSoTimeout, final String user, final String password, final int database,
                           final String clientName, final boolean ssl, final SSLSocketFactory sslSocketFactory,
                           final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {
        this.clientConfig = DefaultJedisClientConfig.builder().connectionTimeoutMillis(connectionTimeout)
                .socketTimeoutMillis(soTimeout).blockingSocketTimeoutMillis(infiniteSoTimeout).user(user)
                .password(password).database(database).clientName(clientName)
                .ssl(ssl).sslSocketFactory(sslSocketFactory)
                .sslParameters(sslParameters).hostnameVerifier(hostnameVerifier).build();
        this.jedisSocketFactory = new DefaultJedisSocketFactory(new HostAndPort(host, port), this.clientConfig);
    }

    protected ReadOnlyJedisFactory(final JedisSocketFactory jedisSocketFactory, final JedisClientConfig clientConfig) {
        this.clientConfig = DefaultJedisClientConfig.copyConfig(clientConfig);
        this.jedisSocketFactory = jedisSocketFactory;
    }

    /**
     * {@link #setHostAndPort(redis.clients.jedis.HostAndPort) setHostAndPort} must be called later.
     */
    protected ReadOnlyJedisFactory(final int connectionTimeout, final int soTimeout, final int infiniteSoTimeout,
                           final String user, final String password, final int database, final String clientName, final boolean ssl,
                           final SSLSocketFactory sslSocketFactory, final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {
        this(DefaultJedisClientConfig.builder().connectionTimeoutMillis(connectionTimeout)
                .socketTimeoutMillis(soTimeout).blockingSocketTimeoutMillis(infiniteSoTimeout).user(user)
                .password(password).database(database).clientName(clientName)
                .ssl(ssl).sslSocketFactory(sslSocketFactory)
                .sslParameters(sslParameters).hostnameVerifier(hostnameVerifier).build());
    }

    /**
     * {@link #setHostAndPort(redis.clients.jedis.HostAndPort) setHostAndPort} must be called later.
     */
    protected ReadOnlyJedisFactory(final JedisClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        this.jedisSocketFactory = new DefaultJedisSocketFactory(clientConfig);
    }

    protected ReadOnlyJedisFactory(final URI uri, final int connectionTimeout, final int soTimeout,
                           final String clientName) {
        this(uri, connectionTimeout, soTimeout, clientName, null, null, null);
    }

    protected ReadOnlyJedisFactory(final URI uri, final int connectionTimeout, final int soTimeout,
                           final String clientName, final SSLSocketFactory sslSocketFactory,
                           final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {
        this(uri, connectionTimeout, soTimeout, 0, clientName, sslSocketFactory, sslParameters, hostnameVerifier);
    }

    protected ReadOnlyJedisFactory(final URI uri, final int connectionTimeout, final int soTimeout,
                           final int infiniteSoTimeout, final String clientName, final SSLSocketFactory sslSocketFactory,
                           final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {
        if (!JedisURIHelper.isValid(uri)) {
            throw new InvalidURIException(String.format(
                    "Cannot open Redis connection due invalid URI. %s", uri.toString()));
        }
        this.clientConfig = DefaultJedisClientConfig.builder().connectionTimeoutMillis(connectionTimeout)
                .socketTimeoutMillis(soTimeout).blockingSocketTimeoutMillis(infiniteSoTimeout)
                .user(JedisURIHelper.getUser(uri)).password(JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri)).clientName(clientName)
                .ssl(JedisURIHelper.isRedisSSLScheme(uri)).sslSocketFactory(sslSocketFactory)
                .sslParameters(sslParameters).hostnameVerifier(hostnameVerifier).build();
        this.jedisSocketFactory = new DefaultJedisSocketFactory(new HostAndPort(uri.getHost(), uri.getPort()), this.clientConfig);
    }

    public void setHostAndPort(final HostAndPort hostAndPort) {
        jedisSocketFactory.updateHostAndPort(hostAndPort);
    }

    public void setPassword(final String password) {
        this.clientConfig.updatePassword(password);
    }

    @Override
    public void activateObject(PooledObject<Jedis> pooledJedis) throws Exception {
        final BinaryJedis jedis = pooledJedis.getObject();
        if (jedis.getDB() != clientConfig.getDatabase()) {
            jedis.select(clientConfig.getDatabase());
            jedis.getClient().readonly();
            jedis.getClient().getStatusCodeReply();
        }
    }

    @Override
    public void destroyObject(PooledObject<Jedis> pooledJedis) throws Exception {
        final BinaryJedis jedis = pooledJedis.getObject();
        if (jedis.isConnected()) {
            try {
                // need a proper test, probably with mock
                if (!jedis.isBroken()) {
                    jedis.quit();
                }
            } catch (Exception e) {
                logger.warn("Error while QUIT", e);
            }
            try {
                jedis.close();
            } catch (Exception e) {
                logger.warn("Error while close", e);
            }
        }
    }

    @Override
    public PooledObject<Jedis> makeObject() throws Exception {
        Jedis jedis = null;
        try {
            jedis = new Jedis(jedisSocketFactory, clientConfig);
            jedis.connect();
            jedis.readonly();
            return new DefaultPooledObject<>(jedis);
        } catch (JedisException je) {
            if (jedis != null) {
                try {
                    jedis.quit();
                } catch (Exception e) {
                    logger.warn("Error while QUIT", e);
                }
                try {
                    jedis.close();
                } catch (Exception e) {
                    logger.warn("Error while close", e);
                }
            }
            throw je;
        }
    }

    @Override
    public void passivateObject(PooledObject<Jedis> pooledJedis) throws Exception {
        // TODO maybe should select db 0? Not sure right now.
    }

    @Override
    public boolean validateObject(PooledObject<Jedis> pooledJedis) {
        final BinaryJedis jedis = pooledJedis.getObject();
        try {
            String host = jedisSocketFactory.getHost();
            int port = jedisSocketFactory.getPort();

            String connectionHost = jedis.getClient().getHost();
            int connectionPort = jedis.getClient().getPort();

            return host.equals(connectionHost)
                    && port == connectionPort && jedis.isConnected()
                    && jedis.ping().equals("PONG");
        } catch (final Exception e) {
            logger.error("Error while validating pooled Jedis object.", e);
            return false;
        }
    }
}
