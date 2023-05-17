/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This class is used in a similar way as ServiceManager, except the services registered here
 * are not Binder objects and are only available in the same process.
 *
 * Once all services are converted to the SystemService interface, this class can be absorbed
 * into SystemServiceManager.
 *
 * {@hide}
 */
public final class LocalServices {
    private LocalServices() {}

    private static final ConcurrentMap<Class<?>, Object> sLocalServiceObjects =
            new ConcurrentHashMap<>();

    /**
     * Returns a local service instance that implements the specified interface.
     *
     * @param type The type of service.
     * @return The service object.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> type) {
        return (T) sLocalServiceObjects.get(type);
    }

    /**
     * Adds a service instance of the specified interface to the global registry of local services.
     */
    public static <T> void addService(Class<T> type, T service) {
        if (sLocalServiceObjects.putIfAbsent(type, service) != null) {
            throw new IllegalStateException("Overriding service registration");
        }
    }

    /**
     * Remove a service instance, must be only used in tests.
     */
    @VisibleForTesting
    public static <T> void removeServiceForTest(Class<T> type) {
        sLocalServiceObjects.remove(type);
    }
}
