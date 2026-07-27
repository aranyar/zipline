/*
 * Copyright (C) 2024 Square, Inc.
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
#ifndef ZIPLINE_RDMA_CHANGE_H
#define ZIPLINE_RDMA_CHANGE_H

#include <jsi/jsi.h>

#include <memory>

// RDMA Changes support, shared by the JNI (Android/JVM) and iOS engine
// layers. Both accumulate RdmaChange entries from JS host functions and
// flush them to the platform sink (redwood-treehouse).
enum class RdmaChangeType {
  Create,
  PropertyChange,
  ModifierChange,
  Add,
  Remove,
  Move,
};

constexpr int RDMA_BATCH_SIZE = 2048;

struct RdmaChange {
  RdmaChangeType type;
  int id;
  int field1;    // tag (Create/Remove/Move), widgetTag (PropertyChange), childrenTag (Add)
  int field2;    // propertyTag (PropertyChange), childId (Add), index (Remove), fromIndex (Move)
  int field3;    // index (Add), toIndex (Move)
  int count;     // count (Move only)
  bool detach;   // detach flag (Remove only)
  // jsi::Value has no copy ctor; we share ownership of the underlying JS
  // value so it stays alive until the change is flushed to the platform.
  std::shared_ptr<facebook::jsi::Value> jsValue;

  RdmaChange() = default;
  RdmaChange(const RdmaChange&) = default;
  RdmaChange& operator=(const RdmaChange&) = default;
  RdmaChange(RdmaChange&&) = default;
  RdmaChange& operator=(RdmaChange&&) = default;
};

#endif  // ZIPLINE_RDMA_CHANGE_H
