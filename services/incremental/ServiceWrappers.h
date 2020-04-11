/*
 * Copyright (C) 2019 The Android Open Source Project
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

#pragma once

#include "IncrementalServiceValidation.h"

#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/content/pm/DataLoaderParamsParcel.h>
#include <android/content/pm/FileSystemControlParcel.h>
#include <android/content/pm/IDataLoader.h>
#include <android/content/pm/IDataLoaderManager.h>
#include <android/content/pm/IDataLoaderStatusListener.h>
#include <android/os/IVold.h>
#include <binder/AppOpsManager.h>
#include <binder/IServiceManager.h>
#include <incfs.h>
#include <jni.h>

#include <memory>
#include <string>
#include <string_view>

using namespace android::incfs;
using namespace android::content::pm;

namespace android::os::incremental {

// --- Wrapper interfaces ---

using MountId = int32_t;

class VoldServiceWrapper {
public:
    virtual ~VoldServiceWrapper() = default;
    virtual binder::Status mountIncFs(const std::string& backingPath, const std::string& targetDir,
                                      int32_t flags,
                                      IncrementalFileSystemControlParcel* _aidl_return) const = 0;
    virtual binder::Status unmountIncFs(const std::string& dir) const = 0;
    virtual binder::Status bindMount(const std::string& sourceDir,
                                     const std::string& targetDir) const = 0;
    virtual binder::Status setIncFsMountOptions(const ::android::os::incremental::IncrementalFileSystemControlParcel& control, bool enableReadLogs) const = 0;
};

class DataLoaderManagerWrapper {
public:
    virtual ~DataLoaderManagerWrapper() = default;
    virtual binder::Status initializeDataLoader(MountId mountId,
                                                const DataLoaderParamsParcel& params,
                                                const FileSystemControlParcel& control,
                                                const sp<IDataLoaderStatusListener>& listener,
                                                bool* _aidl_return) const = 0;
    virtual binder::Status getDataLoader(MountId mountId, sp<IDataLoader>* _aidl_return) const = 0;
    virtual binder::Status destroyDataLoader(MountId mountId) const = 0;
};

class IncFsWrapper {
public:
    virtual ~IncFsWrapper() = default;
    virtual Control createControl(IncFsFd cmd, IncFsFd pendingReads, IncFsFd logs) const = 0;
    virtual ErrorCode makeFile(const Control& control, std::string_view path, int mode, FileId id,
                               NewFileParams params) const = 0;
    virtual ErrorCode makeDir(const Control& control, std::string_view path, int mode) const = 0;
    virtual RawMetadata getMetadata(const Control& control, FileId fileid) const = 0;
    virtual RawMetadata getMetadata(const Control& control, std::string_view path) const = 0;
    virtual FileId getFileId(const Control& control, std::string_view path) const = 0;
    virtual ErrorCode link(const Control& control, std::string_view from,
                           std::string_view to) const = 0;
    virtual ErrorCode unlink(const Control& control, std::string_view path) const = 0;
    virtual base::unique_fd openForSpecialOps(const Control& control, FileId id) const = 0;
    virtual ErrorCode writeBlocks(Span<const DataBlock> blocks) const = 0;
};

class AppOpsManagerWrapper {
public:
    virtual ~AppOpsManagerWrapper() = default;
    virtual binder::Status checkPermission(const char* permission, const char* operation,
                                           const char* package) const = 0;
    virtual void startWatchingMode(int32_t op, const String16& packageName, const sp<IAppOpsCallback>& callback) = 0;
    virtual void stopWatchingMode(const sp<IAppOpsCallback>& callback) = 0;
};

class JniWrapper {
public:
    virtual ~JniWrapper() = default;
    virtual void initializeForCurrentThread() const = 0;
};

class ServiceManagerWrapper {
public:
    virtual ~ServiceManagerWrapper() = default;
    virtual std::unique_ptr<VoldServiceWrapper> getVoldService() = 0;
    virtual std::unique_ptr<DataLoaderManagerWrapper> getDataLoaderManager() = 0;
    virtual std::unique_ptr<IncFsWrapper> getIncFs() = 0;
    virtual std::unique_ptr<AppOpsManagerWrapper> getAppOpsManager() = 0;
    virtual std::unique_ptr<JniWrapper> getJni() = 0;
};

// --- Real stuff ---

class RealServiceManager : public ServiceManagerWrapper {
public:
    RealServiceManager(sp<IServiceManager> serviceManager, JNIEnv* env);
    ~RealServiceManager() = default;
    std::unique_ptr<VoldServiceWrapper> getVoldService() final;
    std::unique_ptr<DataLoaderManagerWrapper> getDataLoaderManager() final;
    std::unique_ptr<IncFsWrapper> getIncFs() final;
    std::unique_ptr<AppOpsManagerWrapper> getAppOpsManager() final;
    std::unique_ptr<JniWrapper> getJni() final;

private:
    template <class INTERFACE>
    sp<INTERFACE> getRealService(std::string_view serviceName) const;
    sp<android::IServiceManager> mServiceManager;
    JavaVM* const mJvm;
};

} // namespace android::os::incremental
