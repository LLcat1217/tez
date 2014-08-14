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
package org.apache.tez.common.security;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.security.GroupMappingServiceProvider;
import org.apache.hadoop.security.ShellBasedUnixGroupsMapping;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tez.dag.api.TezUncheckedException;

import com.google.common.collect.Sets;

/**
 * A user-to-groups mapping service.
 *
 * {@link Groups} allows for server to get the various group memberships
 * of a given user via the {@link #getGroups(String)} call, thus ensuring
 * a consistent user-to-groups mapping and protects against vagaries of
 * different mappings on servers and clients in a Hadoop cluster.
 */
@Private
public class Groups {
  private static final Log LOG = LogFactory.getLog(Groups.class);

  private final GroupMappingServiceProvider impl;

  private final Map<String, CachedGroups> userToGroupsMap =
      new ConcurrentHashMap<String, CachedGroups>();
  private final Map<String, Set<String>> staticUserToGroupsMap =
      new HashMap<String, Set<String>>();
  private final long cacheTimeout;
  private final long warningDeltaMs;

  public Groups(Configuration conf) {
    impl =
        ReflectionUtils.newInstance(
            conf.getClass(CommonConfigurationKeys.HADOOP_SECURITY_GROUP_MAPPING,
                ShellBasedUnixGroupsMapping.class,
                GroupMappingServiceProvider.class),
            conf);

    cacheTimeout =
        conf.getLong(CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_SECS,
            CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_SECS_DEFAULT) * 1000;
    warningDeltaMs =
        conf.getLong(CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_WARN_AFTER_MS,
            CommonConfigurationKeys.HADOOP_SECURITY_GROUPS_CACHE_WARN_AFTER_MS_DEFAULT);
    parseStaticMapping(conf);

    if (cacheTimeout < 0 || warningDeltaMs <= 0) {
      String message = "Invalid values for configuring Groups cache"
          + ", cacheTimeout=" + cacheTimeout
          + ", warningDeltaTimeMs=" + warningDeltaMs;
      LOG.warn(message);
      throw new TezUncheckedException(message);
    }

    if(LOG.isDebugEnabled())
      LOG.debug("Group mapping impl=" + impl.getClass().getName() +
          "; cacheTimeout=" + cacheTimeout + "; warningDeltaMs=" +
          warningDeltaMs);
  }

  /*
   * Parse the hadoop.user.group.static.mapping.overrides configuration to
   * staticUserToGroupsMap
   */
  private void parseStaticMapping(Configuration conf) {
    String staticMapping = conf.get(
        CommonConfigurationKeys.HADOOP_USER_GROUP_STATIC_OVERRIDES,
        CommonConfigurationKeys.HADOOP_USER_GROUP_STATIC_OVERRIDES_DEFAULT);
    Collection<String> mappings = StringUtils.getStringCollection(
        staticMapping, ";");
    for (String users : mappings) {
      Collection<String> userToGroups = StringUtils.getStringCollection(users,
          "=");
      if (userToGroups.size() < 1 || userToGroups.size() > 2) {
        throw new HadoopIllegalArgumentException("Configuration "
            + CommonConfigurationKeys.HADOOP_USER_GROUP_STATIC_OVERRIDES
            + " is invalid");
      }
      String[] userToGroupsArray = userToGroups.toArray(new String[userToGroups
          .size()]);
      String user = userToGroupsArray[0];
      Set<String> groups = Sets.newHashSet();
      if (userToGroupsArray.length == 2) {
        groups.addAll(StringUtils.getStringCollection(userToGroupsArray[1]));
      }
      staticUserToGroupsMap.put(user, groups);
    }
  }

  /**
   * Determine whether the CachedGroups is expired.
   * @param groups cached groups for one user.
   * @return true if groups is expired from useToGroupsMap.
   */
  private boolean hasExpired(CachedGroups groups, long startMs) {
    if (groups == null) {
      return true;
    }
    long timeout = cacheTimeout;
    return groups.getTimestamp() + timeout <= startMs;
  }

  /**
   * Get the group memberships of a given user.
   * @param user User's name
   * @return the group memberships of the user
   * @throws IOException
   */
  public Set<String> getGroups(String user) throws IOException {
    // No need to lookup for groups of static users
    Set<String> staticMapping = staticUserToGroupsMap.get(user);
    if (staticMapping != null) {
      return staticMapping;
    }
    // Return cached value if available
    CachedGroups groups = userToGroupsMap.get(user);
    long startMs = System.currentTimeMillis();
    if (!hasExpired(groups, startMs)) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Returning cached groups for '" + user + "'");
      }
      if (groups.getGroups().isEmpty()) {
        // Even with enabling negative cache, getGroups() has the same behavior
        // that throws IOException if the groups for the user is empty.
        throw new IOException("No groups found for user " + user);
      }
      return groups.getGroups();
    }

    // Create and cache user's groups
    Set<String> groupList = Sets.newHashSet();
    groupList.addAll(impl.getGroups(user));
    long endMs = System.currentTimeMillis();
    long deltaMs = endMs - startMs;
    if (deltaMs > warningDeltaMs) {
      LOG.warn("Potential performance problem: getGroups(user=" + user +") " +
          "took " + deltaMs + " milliseconds.");
    }
    groups = new CachedGroups(groupList, endMs);
    if (groups.getGroups().isEmpty()) {
      throw new IOException("No groups found for user " + user);
    }
    userToGroupsMap.put(user, groups);
    if(LOG.isDebugEnabled()) {
      LOG.debug("Returning fetched groups for '" + user + "'");
    }
    return groups.getGroups();
  }

  /**
   * Refresh all user-to-groups mappings.
   */
  public void refresh() {
    LOG.info("clearing userToGroupsMap cache");
    try {
      impl.cacheGroupsRefresh();
    } catch (IOException e) {
      LOG.warn("Error refreshing groups cache", e);
    }
    userToGroupsMap.clear();
  }

  /**
   * Add groups to cache
   *
   * @param groups list of groups to add to cache
   */
  public void cacheGroupsAdd(List<String> groups) {
    try {
      impl.cacheGroupsAdd(groups);
    } catch (IOException e) {
      LOG.warn("Error caching groups", e);
    }
  }

  /**
   * Class to hold the cached groups
   */
  private static class CachedGroups {
    final long timestamp;
    final Set<String> groups;

    /**
     * Create and initialize group cache
     */
    CachedGroups(Set<String> groups, long timestamp) {
      this.groups = groups;
      this.timestamp = timestamp;
    }

    /**
     * Returns time of last cache update
     *
     * @return time of last cache update
     */
    public long getTimestamp() {
      return timestamp;
    }

    /**
     * Get set of cached groups
     *
     * @return cached groups
     */
    public Set<String> getGroups() {
      return groups;
    }
  }

  private static Groups GROUPS = null;

  /**
   * Get the groups being used to map user-to-groups.
   * @return the groups being used to map user-to-groups.
   */
  public static Groups getUserToGroupsMappingService() {
    return getUserToGroupsMappingService(new Configuration());
  }

  /**
   * Get the groups being used to map user-to-groups.
   * @param conf
   * @return the groups being used to map user-to-groups.
   */
  public static synchronized Groups getUserToGroupsMappingService(
      Configuration conf) {

    if(GROUPS == null) {
      if(LOG.isDebugEnabled()) {
        LOG.debug(" Creating new Groups object");
      }
      GROUPS = new Groups(conf);
    }
    return GROUPS;
  }

  /**
   * Create new groups used to map user-to-groups with loaded configuration.
   * @param conf
   * @return the groups being used to map user-to-groups.
   */
  public static synchronized Groups getUserToGroupsMappingServiceWithLoadedConfiguration(
      Configuration conf) {

    GROUPS = new Groups(conf);
    return GROUPS;
  }
}
