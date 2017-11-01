/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "LoadedApk.h"

#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "format/Archive.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"
#include "format/proto/ProtoDeserialize.h"
#include "io/BigBufferStream.h"
#include "io/Util.h"
#include "xml/XmlDom.h"

using ::aapt::io::IFile;
using ::aapt::io::IFileCollection;
using ::aapt::xml::XmlResource;
using ::android::StringPiece;
using ::std::unique_ptr;

namespace aapt {

std::unique_ptr<LoadedApk> LoadedApk::LoadApkFromPath(const StringPiece& path, IDiagnostics* diag) {
  Source source(path);
  std::string error;
  std::unique_ptr<io::ZipFileCollection> apk = io::ZipFileCollection::Create(path, &error);
  if (apk == nullptr) {
    diag->Error(DiagMessage(path) << "failed opening zip: " << error);
    return {};
  }

  if (apk->FindFile("resources.arsc") != nullptr) {
    return LoadBinaryApkFromFileCollection(source, std::move(apk), diag);
  } else if (apk->FindFile("resources.pb") != nullptr) {
    return LoadProtoApkFromFileCollection(source, std::move(apk), diag);
  }
  diag->Error(DiagMessage(path) << "no resource table found");
  return {};
}

std::unique_ptr<LoadedApk> LoadedApk::LoadProtoApkFromFileCollection(
    const Source& source, unique_ptr<io::IFileCollection> collection, IDiagnostics* diag) {
  io::IFile* table_file = collection->FindFile(kProtoResourceTablePath);
  if (table_file == nullptr) {
    diag->Error(DiagMessage(source) << "failed to find " << kProtoResourceTablePath);
    return {};
  }

  std::unique_ptr<io::InputStream> in = table_file->OpenInputStream();
  if (in == nullptr) {
    diag->Error(DiagMessage(source) << "failed to open " << kProtoResourceTablePath);
    return {};
  }

  pb::ResourceTable pb_table;
  io::ZeroCopyInputAdaptor adaptor(in.get());
  if (!pb_table.ParseFromZeroCopyStream(&adaptor)) {
    diag->Error(DiagMessage(source) << "failed to read " << kProtoResourceTablePath);
    return {};
  }

  std::string error;
  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
  if (!DeserializeTableFromPb(pb_table, collection.get(), table.get(), &error)) {
    diag->Error(DiagMessage(source)
                << "failed to deserialize " << kProtoResourceTablePath << ": " << error);
    return {};
  }

  io::IFile* manifest_file = collection->FindFile(kAndroidManifestPath);
  if (manifest_file == nullptr) {
    diag->Error(DiagMessage(source) << "failed to find " << kAndroidManifestPath);
    return {};
  }

  std::unique_ptr<io::InputStream> manifest_in = manifest_file->OpenInputStream();
  if (manifest_in == nullptr) {
    diag->Error(DiagMessage(source) << "failed to open " << kAndroidManifestPath);
    return {};
  }

  pb::XmlNode pb_node;
  io::ZeroCopyInputAdaptor manifest_adaptor(manifest_in.get());
  if (!pb_node.ParseFromZeroCopyStream(&manifest_adaptor)) {
    diag->Error(DiagMessage(source) << "failed to read proto " << kAndroidManifestPath);
    return {};
  }

  std::unique_ptr<xml::XmlResource> manifest = DeserializeXmlResourceFromPb(pb_node, &error);
  if (manifest == nullptr) {
    diag->Error(DiagMessage(source)
                << "failed to deserialize proto " << kAndroidManifestPath << ": " << error);
    return {};
  }
  return util::make_unique<LoadedApk>(source, std::move(collection), std::move(table),
                                      std::move(manifest));
}

std::unique_ptr<LoadedApk> LoadedApk::LoadBinaryApkFromFileCollection(
    const Source& source, unique_ptr<io::IFileCollection> collection, IDiagnostics* diag) {
  io::IFile* table_file = collection->FindFile(kApkResourceTablePath);
  if (table_file == nullptr) {
    diag->Error(DiagMessage(source) << "failed to find " << kApkResourceTablePath);

    return {};
  }

  std::unique_ptr<io::IData> data = table_file->OpenAsData();
  if (data == nullptr) {
    diag->Error(DiagMessage(source) << "failed to open " << kApkResourceTablePath);
    return {};
  }

  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
  BinaryResourceParser parser(diag, table.get(), source, data->data(), data->size(),
                              collection.get());
  if (!parser.Parse()) {
    return {};
  }

  io::IFile* manifest_file = collection->FindFile(kAndroidManifestPath);
  if (manifest_file == nullptr) {
    diag->Error(DiagMessage(source) << "failed to find " << kAndroidManifestPath);
    return {};
  }

  std::unique_ptr<io::IData> manifest_data = manifest_file->OpenAsData();
  if (manifest_data == nullptr) {
    diag->Error(DiagMessage(source) << "failed to open " << kAndroidManifestPath);
    return {};
  }

  std::string error;
  std::unique_ptr<xml::XmlResource> manifest =
      xml::Inflate(manifest_data->data(), manifest_data->size(), &error);
  if (manifest == nullptr) {
    diag->Error(DiagMessage(source)
                << "failed to parse binary " << kAndroidManifestPath << ": " << error);
    return {};
  }
  return util::make_unique<LoadedApk>(source, std::move(collection), std::move(table),
                                      std::move(manifest));
}

bool LoadedApk::WriteToArchive(IAaptContext* context, const TableFlattenerOptions& options,
                               IArchiveWriter* writer) {
  FilterChain empty;
  return WriteToArchive(context, table_.get(), options, &empty, writer);
}

bool LoadedApk::WriteToArchive(IAaptContext* context, ResourceTable* split_table,
                               const TableFlattenerOptions& options, FilterChain* filters,
                               IArchiveWriter* writer, XmlResource* manifest) {
  std::set<std::string> referenced_resources;
  // List the files being referenced in the resource table.
  for (auto& pkg : split_table->packages) {
    for (auto& type : pkg->types) {
      for (auto& entry : type->entries) {
        for (auto& config_value : entry->values) {
          FileReference* file_ref = ValueCast<FileReference>(config_value->value.get());
          if (file_ref) {
            referenced_resources.insert(*file_ref->path);
          }
        }
      }
    }
  }

  std::unique_ptr<io::IFileCollectionIterator> iterator = apk_->Iterator();
  while (iterator->HasNext()) {
    io::IFile* file = iterator->Next();

    std::string path = file->GetSource().path;
    // The name of the path has the format "<zip-file-name>@<path-to-file>".
    path = path.substr(path.find('@') + 1);

    // Skip resources that are not referenced if requested.
    if (path.find("res/") == 0 && referenced_resources.find(path) == referenced_resources.end()) {
      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(DiagMessage()
                                        << "Removing resource '" << path << "' from APK.");
      }
      continue;
    }

    if (!filters->Keep(path)) {
      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(DiagMessage() << "Filtered '" << path << "' from APK.");
      }
      continue;
    }

    // The resource table needs to be re-serialized since it might have changed.
    if (path == "resources.arsc") {
      BigBuffer buffer(4096);
      // TODO(adamlesinski): How to determine if there were sparse entries (and if to encode
      // with sparse entries) b/35389232.
      TableFlattener flattener(options, &buffer);
      if (!flattener.Consume(context, split_table)) {
        return false;
      }

      io::BigBufferInputStream input_stream(&buffer);
      if (!io::CopyInputStreamToArchive(context, &input_stream, path, ArchiveEntry::kAlign,
                                        writer)) {
        return false;
      }

    } else if (manifest != nullptr && path == "AndroidManifest.xml") {
      BigBuffer buffer(8192);
      XmlFlattener xml_flattener(&buffer, {});
      if (!xml_flattener.Consume(context, manifest)) {
        context->GetDiagnostics()->Error(DiagMessage(path) << "flattening failed");
        return false;
      }

      uint32_t compression_flags = file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
      io::BigBufferInputStream manifest_buffer_in(&buffer);
      if (!io::CopyInputStreamToArchive(context, &manifest_buffer_in, path, compression_flags,
                                        writer)) {
        return false;
      }
    } else {
      uint32_t compression_flags = file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
      if (!io::CopyFileToArchive(context, file, path, compression_flags, writer)) {
        return false;
      }
    }
  }
  return true;
}

}  // namespace aapt
