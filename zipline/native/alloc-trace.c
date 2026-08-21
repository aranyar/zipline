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
#include "alloc-trace.h"

#include "qjs-at-mutex.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define QJS_AT_HAVE_UNWIND 0
#else
#define QJS_AT_HAVE_UNWIND 1
#include <dlfcn.h>
#include <unistd.h>
#include <unwind.h>
#endif

volatile int qjs_at_enabled = 0;
volatile unsigned int qjs_at_sample_rate = 10;
volatile unsigned int qjs_at_generation = 0;

void qjs_at_set_sample_rate(unsigned int rate) {
  qjs_at_sample_rate = rate == 0 ? 1 : rate;
}

static QjsAtMutex qjs_at_mutex = QJS_AT_MUTEX_INIT;
static FILE *qjs_at_out = NULL;
static uintptr_t qjs_at_base = 0;

/* Frame interning: a pushed frame's text is emitted once ("D <id> ..."),
   later pushes reference it as "+ <id>". */
static QjsAtJsFrame *qjs_at_frames = NULL;
static size_t qjs_at_frames_count = 0;
static size_t qjs_at_frames_cap = 0;
#define QJS_AT_INTERN_CAP (1 << 16) /* hash -> frame id + 1 */
static uint32_t *qjs_at_intern = NULL;
static uint64_t qjs_at_alloc_count = 0;
static uint64_t qjs_at_alloc_bytes = 0;
static uint64_t qjs_at_free_count = 0;
static uint64_t qjs_at_free_bytes = 0;
static uint64_t qjs_at_realloc_count = 0;

#if QJS_AT_HAVE_UNWIND
typedef struct {
  uintptr_t *pcs;
  int count;
  int max;
  uintptr_t base;
} QjsAtUnwindContext;

static _Unwind_Reason_Code qjs_at_unwind_callback(struct _Unwind_Context *context, void *arg) {
  QjsAtUnwindContext *u = (QjsAtUnwindContext *)arg;
  uintptr_t pc = (uintptr_t)_Unwind_GetIP(context);
  if (pc != 0) {
    if (u->count < u->max) {
      u->pcs[u->count] = pc >= u->base ? pc - u->base : pc;
      u->count++;
    } else {
      return _URC_END_OF_STACK;
    }
  }
  return _URC_NO_REASON;
}
#endif

static uintptr_t qjs_at_library_base(void) {
#if QJS_AT_HAVE_UNWIND
  Dl_info info;
  if (dladdr((void *)&qjs_at_library_base, &info) && info.dli_fbase) {
    return (uintptr_t)info.dli_fbase;
  }
#endif
  return 0;
}

static int qjs_at_capture_native(uintptr_t *pcs, int max) {
#if QJS_AT_HAVE_UNWIND
  QjsAtUnwindContext u;
  u.pcs = pcs;
  u.count = 0;
  u.max = max;
  u.base = qjs_at_base;
  _Unwind_Backtrace(qjs_at_unwind_callback, &u);
  return u.count;
#else
  (void)pcs;
  (void)max;
  return 0;
#endif
}

static void qjs_at_print_name(FILE *out, const char *name) {
  for (const char *p = name; *p; p++) {
    char c = *p;
    if (c == ';' || c == ',' || c == '=' || c == '\n' || c == '\r') {
      fputc('_', out);
    } else {
      fputc(c, out);
    }
  }
}

static void qjs_at_print_native_stack(FILE *out, int n, const uintptr_t *pcs) {
  for (int i = 0; i < n; i++) {
    if (i > 0) {
      fputc(',', out);
    }
    fprintf(out, "%llx", (unsigned long long)pcs[i]);
  }
}

static uint64_t qjs_at_fnv(const unsigned char *data, size_t len, uint64_t hash) {
  for (size_t i = 0; i < len; i++) {
    hash ^= data[i];
    hash *= 1099511628211ULL;
  }
  return hash;
}

/* Interns [frame]; returns its id, or UINT32_MAX when the arena grows failed. */
static uint32_t qjs_at_intern_frame(const QjsAtJsFrame *frame) {
  uint64_t hash = qjs_at_fnv((const unsigned char *)frame, sizeof(*frame),
                             1469598103934665603ULL);
  size_t index = (size_t)(hash % QJS_AT_INTERN_CAP);
  for (size_t probe = 0; probe < QJS_AT_INTERN_CAP; probe++) {
    uint32_t slot = qjs_at_intern[index];
    if (slot == 0) {
      if (qjs_at_frames_count == qjs_at_frames_cap) {
        size_t new_cap = qjs_at_frames_cap == 0 ? 256 : qjs_at_frames_cap * 2;
        QjsAtJsFrame *grown =
            (QjsAtJsFrame *)realloc(qjs_at_frames, new_cap * sizeof(*grown));
        if (!grown) {
          return UINT32_MAX;
        }
        qjs_at_frames = grown;
        qjs_at_frames_cap = new_cap;
      }
      uint32_t id = (uint32_t)qjs_at_frames_count++;
      qjs_at_frames[id] = *frame;
      qjs_at_intern[index] = id + 1;
      return id;
    }
    if (memcmp(&qjs_at_frames[slot - 1], frame, sizeof(*frame)) == 0) {
      return slot - 1;
    }
    index = (index + 1) % QJS_AT_INTERN_CAP;
  }
  return UINT32_MAX;
}

int qjs_at_start(const char *path) {
  qjs_at_mutex_lock(&qjs_at_mutex);
  if (qjs_at_out) {
    fclose(qjs_at_out);
    qjs_at_out = NULL;
  }
  qjs_at_alloc_count = 0;
  qjs_at_alloc_bytes = 0;
  qjs_at_free_count = 0;
  qjs_at_free_bytes = 0;
  qjs_at_realloc_count = 0;
  qjs_at_frames_count = 0;
  if (qjs_at_intern) {
    memset(qjs_at_intern, 0, QJS_AT_INTERN_CAP * sizeof(*qjs_at_intern));
  } else {
    qjs_at_intern = (uint32_t *)calloc(QJS_AT_INTERN_CAP, sizeof(uint32_t));
  }
  qjs_at_base = qjs_at_library_base();
  qjs_at_out = fopen(path, "w");
  if (qjs_at_out) {
    setvbuf(qjs_at_out, NULL, _IOFBF, 1 << 20);
  }
  qjs_at_generation++;
  qjs_at_enabled = qjs_at_out != NULL;
  if (qjs_at_out) {
    fprintf(qjs_at_out, "# qjs-alloc-trace v2 sample_rate=%u base=0x%llx\n",
            qjs_at_sample_rate, (unsigned long long)qjs_at_base);
  }
  qjs_at_mutex_unlock(&qjs_at_mutex);
  return qjs_at_out ? 0 : -1;
}

/* Writes a heap-snapshot marker ("H") into the stream; the live heap at each
   marker is computed offline from the event stream
   (alloc_trace_flamegraph.py --metric retained [--heap-at N]). */
void qjs_at_dump_heap(void) {
  qjs_at_mutex_lock(&qjs_at_mutex);
  if (qjs_at_out) {
    fputs("H\n", qjs_at_out);
    fflush(qjs_at_out);
  }
  qjs_at_mutex_unlock(&qjs_at_mutex);
}

void qjs_at_stop(void) {
  qjs_at_mutex_lock(&qjs_at_mutex);
  qjs_at_enabled = 0;
  if (qjs_at_out) {
    fprintf(qjs_at_out,
            "# totals allocs=%llu alloc_bytes=%llu frees=%llu free_bytes=%llu reallocs=%llu\n",
            (unsigned long long)qjs_at_alloc_count,
            (unsigned long long)qjs_at_alloc_bytes,
            (unsigned long long)qjs_at_free_count,
            (unsigned long long)qjs_at_free_bytes,
            (unsigned long long)qjs_at_realloc_count);
    fflush(qjs_at_out);
#if QJS_AT_HAVE_UNWIND
    fsync(fileno(qjs_at_out));
#endif
    fclose(qjs_at_out);
    qjs_at_out = NULL;
  }
  qjs_at_mutex_unlock(&qjs_at_mutex);
}

void qjs_at_begin(void) {
  qjs_at_mutex_lock(&qjs_at_mutex);
}

void qjs_at_commit(void) {
  qjs_at_mutex_unlock(&qjs_at_mutex);
}

/* Frame text is defined once as "D <id> ..."; pushes reference the id. */
void qjs_at_stack_push(const QjsAtJsFrame *frame) {
  if (qjs_at_out) {
    uint32_t id = qjs_at_intern_frame(frame);
    if (id == UINT32_MAX) {
      return;
    }
    if (id + 1 == qjs_at_frames_count) {
      fprintf(qjs_at_out, "D %u ", id);
      if (frame->is_native) {
        fputs("<native>", qjs_at_out);
      } else {
        qjs_at_print_name(qjs_at_out, frame->func_name[0] ? frame->func_name : "<anonymous>");
        fputc('@', qjs_at_out);
        qjs_at_print_name(qjs_at_out, frame->filename[0] ? frame->filename : "?");
        if (frame->line_num) {
          fprintf(qjs_at_out, ":%u", frame->line_num);
        }
      }
      fputc('\n', qjs_at_out);
    }
    fprintf(qjs_at_out, "+ %u\n", id);
  }
}

void qjs_at_stack_pop(int n) {
  if (qjs_at_out) {
    fprintf(qjs_at_out, "- %d\n", n);
  }
}

/* Must be called between qjs_at_begin/qjs_at_commit (lock held). */
void qjs_at_event(int kind, const void *ptr, const void *ptr2, size_t size) {
  uintptr_t native_pcs[QJS_AT_MAX_NATIVE_FRAMES];
  int n_native;

  if (!qjs_at_enabled || !qjs_at_out) {
    return;
  }
  /* The stack of a free carries no information: the event is attributed to
     the allocation's stack when replaying the stream. */
  n_native = kind == QJS_AT_FREE
      ? 0
      : qjs_at_capture_native(native_pcs, QJS_AT_MAX_NATIVE_FRAMES);

  if (kind == QJS_AT_ALLOC) {
    qjs_at_alloc_count++;
    qjs_at_alloc_bytes += size;
    fprintf(qjs_at_out, "A %p %llu native=", ptr, (unsigned long long)size);
    qjs_at_print_native_stack(qjs_at_out, n_native, native_pcs);
    fputc('\n', qjs_at_out);
  } else if (kind == QJS_AT_FREE) {
    qjs_at_free_count++;
    qjs_at_free_bytes += size;
    fprintf(qjs_at_out, "F %p\n", ptr);
  } else if (kind == QJS_AT_REALLOC) {
    qjs_at_realloc_count++;
    fprintf(qjs_at_out, "R %p %p %llu native=", ptr, ptr2, (unsigned long long)size);
    qjs_at_print_native_stack(qjs_at_out, n_native, native_pcs);
    fputc('\n', qjs_at_out);
  }
}
