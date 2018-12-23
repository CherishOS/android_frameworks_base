/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <fstream>
#include <memory>
#include <ostream>
#include <string>

#include "android-base/macros.h"
#include "utils/String8.h"
#include "utils/Trace.h"

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"

#include "idmap2d/Idmap2Service.h"

using android::binder::Status;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::Idmap;
using android::idmap2::IdmapHeader;
using android::idmap2::utils::kIdmapFilePermissionMask;

namespace {

constexpr const char* kIdmapCacheDir = "/data/resource-cache";

Status ok() {
  return Status::ok();
}

Status error(const std::string& msg) {
  LOG(ERROR) << msg;
  return Status::fromExceptionCode(Status::EX_NONE, msg.c_str());
}

}  // namespace

namespace android::os {

Status Idmap2Service::getIdmapPath(const std::string& overlay_apk_path,
                                   int32_t user_id ATTRIBUTE_UNUSED, std::string* _aidl_return) {
  assert(_aidl_return);
  *_aidl_return = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  return ok();
}

Status Idmap2Service::removeIdmap(const std::string& overlay_apk_path,
                                  int32_t user_id ATTRIBUTE_UNUSED, bool* _aidl_return) {
  assert(_aidl_return);
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  if (unlink(idmap_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + idmap_path + ": " + strerror(errno));
  }
  *_aidl_return = true;
  return ok();
}

Status Idmap2Service::verifyIdmap(const std::string& overlay_apk_path,
                                  int32_t user_id ATTRIBUTE_UNUSED, bool* _aidl_return) {
  assert(_aidl_return);
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  std::ifstream fin(idmap_path);
  const std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(fin);
  fin.close();
  std::stringstream dev_null;
  *_aidl_return = header && header->IsUpToDate(dev_null);
  return ok();
}

Status Idmap2Service::createIdmap(const std::string& target_apk_path,
                                  const std::string& overlay_apk_path, int32_t user_id,
                                  std::unique_ptr<std::string>* _aidl_return) {
  assert(_aidl_return);
  std::stringstream trace;
  trace << __FUNCTION__ << " " << target_apk_path << " " << overlay_apk_path << " "
        << std::to_string(user_id);
  ATRACE_NAME(trace.str().c_str());
  std::cout << trace.str() << std::endl;

  _aidl_return->reset(nullptr);

  const std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    return error("failed to load apk " + target_apk_path);
  }

  const std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    return error("failed to load apk " + overlay_apk_path);
  }

  std::stringstream err;
  const std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk, err);
  if (!idmap) {
    return error(err.str());
  }

  umask(kIdmapFilePermissionMask);
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  std::ofstream fout(idmap_path);
  if (fout.fail()) {
    return error("failed to open idmap path " + idmap_path);
  }
  BinaryStreamVisitor visitor(fout);
  idmap->accept(&visitor);
  fout.close();
  if (fout.fail()) {
    return error("failed to write to idmap path " + idmap_path);
  }

  *_aidl_return = std::make_unique<std::string>(idmap_path);
  return ok();
}

}  // namespace android::os
