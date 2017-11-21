/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

/**
  * Binder interface to communicate with the statistics management service.
  * {@hide}
  */
interface IStatsManager {
    /**
     * Tell the stats daemon that the android system server is up and running.
     */
    oneway void systemRunning();

    /**
     * Tell the stats daemon that the StatsCompanionService is up and running.
     * Two-way binder call so that caller knows message received.
     */
    void statsCompanionReady();

    /**
     * Tells statsd that an anomaly may have occurred, so statsd can check whether this is so and
     * act accordingly.
     * Two-way binder call so that caller's method (and corresponding wakelocks) will linger.
     */
    void informAnomalyAlarmFired();

    /**
     * Tells statsd that it is time to poll some stats. Statsd will be responsible for determing
     * what stats to poll and initiating the polling.
     * Two-way binder call so that caller's method (and corresponding wakelocks) will linger.
     */
    void informPollAlarmFired();

    /**
     * Inform statsd what the version and package are for each uid. Note that each array should
     * have the same number of elements, and version[i] and package[i] correspond to uid[i].
     */
    oneway void informAllUidData(in int[] uid, in int[] version, in String[] app);

    /**
     * Inform statsd what the uid and version are for one app that was updated.
     */
    oneway void informOnePackage(in String app, in int uid, in int version);

    /**
     * Inform stats that an app was removed.
     */
    oneway void informOnePackageRemoved(in String app, in int uid);

    /**
     * Fetches data for the specified configuration key. Returns a byte array representing proto
     * wire-encoded of ConfigMetricsReport.
     */
    byte[] getData(in String key);

    /**
     * Sets a configuration with the specified config key and subscribes to updates for this
     * configuration key. Broadcasts will be sent if this configuration needs to be collected.
     * The configuration must be a wire-encoded StatsDConfig. The caller specifies the name of the
     * package and class that should receive these broadcasts.
     *
     * Returns if this configuration was correctly registered.
     */
    boolean addConfiguration(in String configKey, in byte[] config, in String pkg, in String cls);

    /**
     * Removes the configuration with the matching config key. No-op if this config key does not
     * exist.
     *
     * Returns if this configuration key was removed.
     */
    boolean removeConfiguration(in String configKey);
}
