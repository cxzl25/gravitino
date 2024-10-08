/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.hive;

import com.google.common.annotations.VisibleForTesting;
import org.apache.gravitino.hive.dyn.DynMethods;
import org.apache.gravitino.hive.dyn.DynMethods.StaticMethod;
import org.apache.gravitino.utils.ClientPoolImpl;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaHookLoader;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.RetryingMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// hive-metastore/src/main/java/org/apache/iceberg/hive/HiveClientPool.java

/** Represents a client pool for managing connections to an Apache Hive Metastore service. */
public class HiveClientPool extends ClientPoolImpl<IMetaStoreClient, TException> {

  private static final Logger LOG = LoggerFactory.getLogger(HiveClientPool.class);
  private static final StaticMethod GET_CLIENT =
      DynMethods.builder("getProxy")
          .impl(
              RetryingMetaStoreClient.class,
              HiveConf.class,
              HiveMetaHookLoader.class,
              String.class) // Hive 1 and 2
          .impl(
              RetryingMetaStoreClient.class,
              Configuration.class,
              HiveMetaHookLoader.class,
              String.class) // Hive 3
          .buildStatic();

  private final HiveConf hiveConf;

  /**
   * Creates a new HiveClientPool with the specified pool size and configuration.
   *
   * @param poolSize The number of clients in the pool.
   * @param conf The configuration used to initialize the Hive Metastore clients.
   */
  public HiveClientPool(int poolSize, Configuration conf) {
    // Do not allow retry by default as we rely on RetryingHiveClient
    super(poolSize, TTransportException.class, false);
    this.hiveConf = new HiveConf(conf, HiveClientPool.class);
    this.hiveConf.addResource(conf);
  }

  @Override
  protected IMetaStoreClient newClient() {
    try {
      try {
        return GET_CLIENT.invoke(
            hiveConf, (HiveMetaHookLoader) tbl -> null, HiveMetaStoreClient.class.getName());
      } catch (RuntimeException e) {
        // any MetaException would be wrapped into RuntimeException during reflection, so let's
        // double-check type here
        if (e.getCause() instanceof MetaException) {
          throw (MetaException) e.getCause();
        }
        throw e;
      }
    } catch (MetaException e) {
      throw new RuntimeException("Failed to connect to Hive Metastore", e);
    } catch (Exception e) {
      if (e.getMessage().contains("Another instance of Derby may have already booted")) {
        throw new RuntimeException(
            "Failed to start an embedded metastore because embedded "
                + "Derby supports only one client at a time. To fix this, use a metastore that supports "
                + "multiple clients.",
            e);
      }

      throw new RuntimeException("Failed to connect to Hive Metastore", e);
    }
  }

  @Override
  protected IMetaStoreClient reconnect(IMetaStoreClient client) {
    LOG.warn("Reconnecting to Hive Metastore");
    try {
      client.close();
      client.reconnect();
    } catch (MetaException e) {
      throw new RuntimeException("Failed to reconnect to Hive Metastore", e);
    }
    return client;
  }

  @Override
  protected boolean isConnectionException(Exception e) {
    return super.isConnectionException(e)
        || (e instanceof MetaException
            && e.getMessage()
                .contains("Got exception: org.apache.thrift.transport.TTransportException"));
  }

  @Override
  protected void close(IMetaStoreClient client) {
    LOG.info("Closing Hive Metastore client");
    client.close();
  }

  /**
   * Gets the HiveConf object used in this HiveClientPool.
   *
   * @return The HiveConf object used in this HiveClientPool.
   */
  @VisibleForTesting
  HiveConf hiveConf() {
    return hiveConf;
  }
}
