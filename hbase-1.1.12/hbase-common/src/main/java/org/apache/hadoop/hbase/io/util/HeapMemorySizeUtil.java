/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.io.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;

@InterfaceAudience.Private
public class HeapMemorySizeUtil {

  public static final String MEMSTORE_SIZE_KEY = "hbase.regionserver.global.memstore.size";
  public static final String MEMSTORE_SIZE_OLD_KEY =
      "hbase.regionserver.global.memstore.upperLimit";
  public static final String MEMSTORE_SIZE_LOWER_LIMIT_KEY =
      "hbase.regionserver.global.memstore.size.lower.limit";
  public static final String MEMSTORE_SIZE_LOWER_LIMIT_OLD_KEY =
      "hbase.regionserver.global.memstore.lowerLimit";

  public static final float DEFAULT_MEMSTORE_SIZE = 0.4f;
  // Default lower water mark limit is 95% size of memstore size.
  public static final float DEFAULT_MEMSTORE_SIZE_LOWER_LIMIT = 0.95f;

  private static final Log LOG = LogFactory.getLog(HeapMemorySizeUtil.class);
  // a constant to convert a fraction to a percentage
  private static final int CONVERT_TO_PERCENTAGE = 100;

  private static final String JVM_HEAP_EXCEPTION = "Got an exception while attempting to read " +
      "information about the JVM heap. Please submit this log information in a bug report and " +
      "include your JVM settings, specifically the GC in use and any -XX options. Consider " +
      "restarting the service.";

  /**
   * Return JVM memory statistics while properly handling runtime exceptions from the JVM.
   * @return a memory usage object, null if there was a runtime exception. (n.b. you
   *         could also get -1 values back from the JVM)
   * @see MemoryUsage
   */
  public static MemoryUsage safeGetHeapMemoryUsage() {
    MemoryUsage usage = null;
    try {
      usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    } catch (RuntimeException exception) {
      LOG.warn(JVM_HEAP_EXCEPTION, exception);
    }
    return usage;
  }

  /**
   * Checks whether we have enough heap memory left out after portion for Memstore and Block cache.
   * We need atleast 20% of heap left out for other RS functions.
   * @param conf
   */
  public static void checkForClusterFreeMemoryLimit(Configuration conf) {
    if (conf.get(MEMSTORE_SIZE_OLD_KEY) != null) {
      LOG.warn(MEMSTORE_SIZE_OLD_KEY + " is deprecated by " + MEMSTORE_SIZE_KEY);
    }
    float globalMemstoreSize = getGlobalMemStorePercent(conf, false);
    int gml = (int)(globalMemstoreSize * CONVERT_TO_PERCENTAGE);
    float blockCacheUpperLimit = getBlockCacheHeapPercent(conf);
    int bcul = (int)(blockCacheUpperLimit * CONVERT_TO_PERCENTAGE);
    if (CONVERT_TO_PERCENTAGE - (gml + bcul)
            < (int)(CONVERT_TO_PERCENTAGE *
                    HConstants.HBASE_CLUSTER_MINIMUM_MEMORY_THRESHOLD)) {
      throw new RuntimeException("Current heap configuration for MemStore and BlockCache exceeds "
          + "the threshold required for successful cluster operation. "
          + "The combined value cannot exceed 0.8. Please check "
          + "the settings for hbase.regionserver.global.memstore.size and "
          + "hfile.block.cache.size in your configuration. "
          + "hbase.regionserver.global.memstore.size is " + globalMemstoreSize
          + " hfile.block.cache.size is " + blockCacheUpperLimit);
    }
  }

  /**
   * Retrieve global memstore configured size as percentage of total heap.
   * @param c
   * @param logInvalid
   */
  public static float getGlobalMemStorePercent(final Configuration c, final boolean logInvalid) {
    float limit = c.getFloat(MEMSTORE_SIZE_KEY,
        c.getFloat(MEMSTORE_SIZE_OLD_KEY, DEFAULT_MEMSTORE_SIZE));
    if (limit > 0.8f || limit <= 0.0f) {
      if (logInvalid) {
        LOG.warn("Setting global memstore limit to default of " + DEFAULT_MEMSTORE_SIZE
            + " because supplied value outside allowed range of (0 -> 0.8]");
      }
      limit = DEFAULT_MEMSTORE_SIZE;
    }
    return limit;
  }

  /**
   * Retrieve configured size for global memstore lower water mark as percentage of total heap.
   * @param c
   * @param globalMemStorePercent
   */
  public static float getGlobalMemStoreLowerMark(final Configuration c, float globalMemStorePercent) {
    String lowMarkPercentStr = c.get(MEMSTORE_SIZE_LOWER_LIMIT_KEY);
    if (lowMarkPercentStr != null) {
      return Float.parseFloat(lowMarkPercentStr);
    }
    String lowerWaterMarkOldValStr = c.get(MEMSTORE_SIZE_LOWER_LIMIT_OLD_KEY);
    if (lowerWaterMarkOldValStr != null) {
      LOG.warn(MEMSTORE_SIZE_LOWER_LIMIT_OLD_KEY + " is deprecated. Instead use "
          + MEMSTORE_SIZE_LOWER_LIMIT_KEY);
      float lowerWaterMarkOldVal = Float.parseFloat(lowerWaterMarkOldValStr);
      if (lowerWaterMarkOldVal > globalMemStorePercent) {
        lowerWaterMarkOldVal = globalMemStorePercent;
        LOG.info("Setting globalMemStoreLimitLowMark == globalMemStoreLimit " + "because supplied "
            + MEMSTORE_SIZE_LOWER_LIMIT_OLD_KEY + " was > " + MEMSTORE_SIZE_OLD_KEY);
      }
      return lowerWaterMarkOldVal / globalMemStorePercent;
    }
    return DEFAULT_MEMSTORE_SIZE_LOWER_LIMIT;
  }

  /**
   * Retrieve configured size for on heap block cache as percentage of total heap.
   * @param conf
   */
  public static float getBlockCacheHeapPercent(final Configuration conf) {
    // L1 block cache is always on heap
    float l1CachePercent = conf.getFloat(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY,
        HConstants.HFILE_BLOCK_CACHE_SIZE_DEFAULT);
    float l2CachePercent = getL2BlockCacheHeapPercent(conf);
    return l1CachePercent + l2CachePercent;
  }

  /**
   * @param conf
   * @return The on heap size for L2 block cache.
   */
  public static float getL2BlockCacheHeapPercent(Configuration conf) {
    float l2CachePercent = 0.0F;
    String bucketCacheIOEngineName = conf.get(HConstants.BUCKET_CACHE_IOENGINE_KEY, null);
    // L2 block cache can be on heap when IOEngine is "heap"
    if (bucketCacheIOEngineName != null && bucketCacheIOEngineName.startsWith("heap")) {
      float bucketCachePercentage = conf.getFloat(HConstants.BUCKET_CACHE_SIZE_KEY, 0F);
      long max = -1L;
      final MemoryUsage usage = safeGetHeapMemoryUsage();
      if (usage != null) {
        max = usage.getMax();
      }
      l2CachePercent = bucketCachePercentage < 1 ? bucketCachePercentage
          : (bucketCachePercentage * 1024 * 1024) / max;
    }
    return l2CachePercent;
  }

  /**
   * @param conf used to read cache configs
   * @return the number of bytes to use for LRU, negative if disabled.
   * @throws IllegalArgumentException if HFILE_BLOCK_CACHE_SIZE_KEY is > 1.0
   */
  public static long getLruCacheSize(final Configuration conf) {
    float cachePercentage = conf.getFloat(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY,
      HConstants.HFILE_BLOCK_CACHE_SIZE_DEFAULT);
    if (cachePercentage <= 0.0001f) {
      return -1;
    }
    if (cachePercentage > 1.0) {
      throw new IllegalArgumentException(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY +
        " must be between 0.0 and 1.0, and not > 1.0");
    }
    long max = -1L;
    final MemoryUsage usage = safeGetHeapMemoryUsage();
    if (usage != null) {
      max = usage.getMax();
    }

    // Calculate the amount of heap to give the heap.
    return (long) (max * cachePercentage);
  }

  /**
   * @param conf used to read config for bucket cache size. (< 1 is treated as % and > is treated as MiB)
   * @return the number of bytes to use for bucket cache, negative if disabled.
   */
  public static long getBucketCacheSize(final Configuration conf) {
    final float bucketCachePercentage = conf.getFloat(HConstants.BUCKET_CACHE_SIZE_KEY, 0F);
    long bucketCacheSize;
    // Values < 1 are treated as % of heap
    if (bucketCachePercentage < 1) {
      long max = -1L;
      final MemoryUsage usage = safeGetHeapMemoryUsage();
      if (usage != null) {
        max = usage.getMax();
      }
      bucketCacheSize = (long)(max * bucketCachePercentage);
    // values >= 1 are treated as # of MiB
    } else {
      bucketCacheSize = (long)(bucketCachePercentage * 1024 * 1024);
    }
    return bucketCacheSize;
  }

}
