/*
 * Copyright (C) 2019 Square, Inc.
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
#ifndef QJS_ALLOC_TRACE_H
#define QJS_ALLOC_TRACE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define QJS_AT_MAX_JS_FRAMES 128
#define QJS_AT_MAX_NATIVE_FRAMES 16
#define QJS_AT_JS_NAME_LEN 64

enum {
  QJS_AT_ALLOC = 1,
  QJS_AT_FREE = 2,
  QJS_AT_REALLOC = 3
};

typedef struct {
  char func_name[QJS_AT_JS_NAME_LEN];
  char filename[QJS_AT_JS_NAME_LEN];
  uint32_t line_num; /* 0 = unknown */
  uint8_t is_native;
} QjsAtJsFrame;

extern volatile int qjs_at_enabled;

/* Record 1 of every N events; 1 = record everything. Checked before stack capture. */
extern volatile unsigned int qjs_at_sample_rate;

void qjs_at_set_sample_rate(unsigned int rate);

static inline int qjs_at_sample(void) {
  static unsigned int counter = 0;
  unsigned int rate = qjs_at_sample_rate;
  if (rate <= 1) {
    return 1;
  }
  return (++counter % rate) == 0;
}

void qjs_at_start(void);
void qjs_at_start_aggregated(void);
void qjs_at_stop(void);

void qjs_at_record(int kind, const void *ptr, const void *ptr2, size_t size,
                   const QjsAtJsFrame *js_frames, int n_js_frames);

int qjs_at_dump(const char *path);

#ifdef __cplusplus
}
#endif

#endif
