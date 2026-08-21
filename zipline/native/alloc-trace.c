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

#include <pthread.h>
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

typedef struct {
  uint64_t seq;
  uint64_t size;
  const void *ptr;
  const void *ptr2;
  uint8_t kind;
  uint8_t n_js;
  uint8_t n_native;
  uintptr_t native_pcs[QJS_AT_MAX_NATIVE_FRAMES];
  QjsAtJsFrame js_frames[QJS_AT_MAX_JS_FRAMES];
} QjsAtEvent;

typedef struct {
  uint64_t alloc_count;
  uint64_t alloc_bytes;
  uint64_t free_count;
  uint64_t free_bytes;
  uint64_t realloc_count;
  uint64_t live_count; /* allocs minus matched frees, attributed to the alloc stack */
  uint64_t live_bytes;
  uint32_t js_off; /* start index in the frame-id arena */
  uint8_t n_js;
  uint8_t n_native;
  uint8_t occupied;
  uintptr_t native_pcs[QJS_AT_MAX_NATIVE_FRAMES];
} QjsAtBucket;

/* Live pointer map: ptr -> (allocation stack bucket, requested size). */
typedef struct {
  const void *ptr;
  QjsAtBucket *bucket;
  uint64_t size;
} QjsAtPtrEntry;

#define QJS_AT_PTR_MAP_SIZE (1 << 23) /* 8M entries, power of two for mask probing */
#define QJS_AT_PTR_TOMBSTONE ((const void *)1)

#define QJS_AT_CAPACITY 131072
#define QJS_AT_BUCKETS (QJS_AT_CAPACITY * 2)
#define QJS_AT_DUMP_MAX_BUCKETS QJS_AT_CAPACITY
/* Unique (deduplicated) JS frames; 20M x 65B, mostly untouched zero pages. */
#define QJS_AT_FRAME_ARENA (20 * 1024 * 1024)
/* Open-addressing intern table: frame content hash -> frame arena index + 1. */
#define QJS_AT_INTERN_SIZE (1 << 25)
/* Per-bucket lists of interned frame ids. */
#define QJS_AT_ID_ARENA (16 * 1024 * 1024)

#define QJS_AT_MODE_RAW 1
#define QJS_AT_MODE_AGGREGATE 2

volatile int qjs_at_enabled = 0;
volatile unsigned int qjs_at_sample_rate = 10;

void qjs_at_set_sample_rate(unsigned int rate) {
  qjs_at_sample_rate = rate == 0 ? 1 : rate;
}

static pthread_mutex_t qjs_at_mutex = PTHREAD_MUTEX_INITIALIZER;
static int qjs_at_mode = 0;
static QjsAtEvent *qjs_at_events = NULL;
static QjsAtBucket *qjs_at_buckets = NULL;
static QjsAtJsFrame *qjs_at_frame_arena = NULL;
static size_t qjs_at_frame_arena_used = 0;
static uint32_t *qjs_at_intern_table = NULL;
static uint32_t *qjs_at_id_arena = NULL;
static size_t qjs_at_id_arena_used = 0;
static QjsAtPtrEntry *qjs_at_ptr_map = NULL;
static size_t qjs_at_ptr_map_used = 0; /* live entries + tombstones */
static uint64_t qjs_at_unmatched_frees = 0;
static uint64_t qjs_at_ptr_dropped = 0;
static uint64_t qjs_at_next_seq = 0;
static size_t qjs_at_count = 0;
static size_t qjs_at_write = 0;
static uint64_t qjs_at_dropped = 0;
static uintptr_t qjs_at_base = 0;
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

static void qjs_at_reset(void) {
  free(qjs_at_events);
  qjs_at_events = NULL;
  free(qjs_at_buckets);
  qjs_at_buckets = NULL;
  free(qjs_at_frame_arena);
  qjs_at_frame_arena = NULL;
  qjs_at_frame_arena_used = 0;
  free(qjs_at_intern_table);
  qjs_at_intern_table = NULL;
  free(qjs_at_id_arena);
  qjs_at_id_arena = NULL;
  qjs_at_id_arena_used = 0;
  free(qjs_at_ptr_map);
  qjs_at_ptr_map = NULL;
  qjs_at_ptr_map_used = 0;
  qjs_at_unmatched_frees = 0;
  qjs_at_ptr_dropped = 0;
  qjs_at_next_seq = 0;
  qjs_at_count = 0;
  qjs_at_write = 0;
  qjs_at_dropped = 0;
  qjs_at_alloc_count = 0;
  qjs_at_alloc_bytes = 0;
  qjs_at_free_count = 0;
  qjs_at_free_bytes = 0;
  qjs_at_realloc_count = 0;
  qjs_at_base = qjs_at_library_base();
}

void qjs_at_start(void) {
  pthread_mutex_lock(&qjs_at_mutex);
  qjs_at_reset();
  qjs_at_events = (QjsAtEvent *)calloc(QJS_AT_CAPACITY, sizeof(QjsAtEvent));
  qjs_at_mode = QJS_AT_MODE_RAW;
  qjs_at_enabled = qjs_at_events != NULL;
  pthread_mutex_unlock(&qjs_at_mutex);
}

void qjs_at_start_aggregated(void) {
  pthread_mutex_lock(&qjs_at_mutex);
  qjs_at_reset();
  qjs_at_buckets = (QjsAtBucket *)calloc(QJS_AT_BUCKETS, sizeof(QjsAtBucket));
  qjs_at_frame_arena =
      (QjsAtJsFrame *)calloc(QJS_AT_FRAME_ARENA, sizeof(QjsAtJsFrame));
  qjs_at_intern_table = (uint32_t *)calloc(QJS_AT_INTERN_SIZE, sizeof(uint32_t));
  qjs_at_id_arena = (uint32_t *)calloc(QJS_AT_ID_ARENA, sizeof(uint32_t));
  qjs_at_ptr_map = (QjsAtPtrEntry *)calloc(QJS_AT_PTR_MAP_SIZE, sizeof(QjsAtPtrEntry));
  qjs_at_mode = QJS_AT_MODE_AGGREGATE;
  qjs_at_enabled = qjs_at_buckets != NULL && qjs_at_frame_arena != NULL &&
                   qjs_at_intern_table != NULL && qjs_at_id_arena != NULL &&
                   qjs_at_ptr_map != NULL;
  pthread_mutex_unlock(&qjs_at_mutex);
}

void qjs_at_stop(void) {
  qjs_at_enabled = 0;
}

static uint64_t qjs_at_fnv(const unsigned char *data, size_t len, uint64_t hash) {
  for (size_t i = 0; i < len; i++) {
    hash ^= data[i];
    hash *= 1099511628211ULL;
  }
  return hash;
}

static uint64_t qjs_at_hash_stack(int n_js, const uint32_t *js_ids,
                                  int n_native, const uintptr_t *native_pcs) {
  uint64_t hash = 1469598103934665603ULL;
  hash = qjs_at_fnv((const unsigned char *)js_ids,
                    (size_t)n_js * sizeof(uint32_t), hash);
  hash = qjs_at_fnv((const unsigned char *)native_pcs,
                    (size_t)n_native * sizeof(uintptr_t), hash);
  hash ^= (uint64_t)n_js;
  hash *= 1099511628211ULL;
  hash ^= (uint64_t)n_native;
  hash *= 1099511628211ULL;
  return hash;
}

/* Returns the interned frame id, or UINT32_MAX if the frame arena is full. */
static uint32_t qjs_at_intern_frame(const QjsAtJsFrame *frame) {
  uint64_t hash = qjs_at_fnv((const unsigned char *)frame, sizeof(*frame),
                             1469598103934665603ULL);
  size_t index = (size_t)(hash % QJS_AT_INTERN_SIZE);
  for (size_t probe = 0; probe < QJS_AT_INTERN_SIZE; probe++) {
    uint32_t slot = qjs_at_intern_table[index];
    if (slot == 0) {
      uint32_t id;
      if (qjs_at_frame_arena_used >= QJS_AT_FRAME_ARENA) {
        return UINT32_MAX;
      }
      id = (uint32_t)qjs_at_frame_arena_used++;
      qjs_at_frame_arena[id] = *frame;
      qjs_at_intern_table[index] = id + 1;
      return id;
    }
    if (memcmp(&qjs_at_frame_arena[slot - 1], frame, sizeof(*frame)) == 0) {
      return slot - 1;
    }
    index = (index + 1) % QJS_AT_INTERN_SIZE;
  }
  return UINT32_MAX;
}

static size_t qjs_at_ptr_hash(const void *ptr) {
  uint64_t h = ((uintptr_t)ptr >> 4) * 11400714819323198485ULL;
  return (size_t)(h >> 22); /* top 42 bits; masked by caller */
}

static void qjs_at_ptr_insert(const void *ptr, QjsAtBucket *bucket,
                              uint64_t size) {
  size_t index;
  if (qjs_at_ptr_map_used >= (QJS_AT_PTR_MAP_SIZE * 3) / 4) {
    qjs_at_ptr_dropped++;
    return;
  }
  index = qjs_at_ptr_hash(ptr) & (QJS_AT_PTR_MAP_SIZE - 1);
  for (size_t probe = 0; probe < QJS_AT_PTR_MAP_SIZE; probe++) {
    QjsAtPtrEntry *entry = &qjs_at_ptr_map[index];
    if (entry->ptr == NULL || entry->ptr == QJS_AT_PTR_TOMBSTONE) {
      entry->ptr = ptr;
      entry->bucket = bucket;
      entry->size = size;
      qjs_at_ptr_map_used++;
      return;
    }
    if (entry->ptr == ptr) {
      entry->bucket = bucket;
      entry->size = size;
      return;
    }
    index = (index + 1) & (QJS_AT_PTR_MAP_SIZE - 1);
  }
  qjs_at_ptr_dropped++;
}

static void qjs_at_ptr_remove(const void *ptr) {
  size_t index = qjs_at_ptr_hash(ptr) & (QJS_AT_PTR_MAP_SIZE - 1);
  for (size_t probe = 0; probe < QJS_AT_PTR_MAP_SIZE; probe++) {
    QjsAtPtrEntry *entry = &qjs_at_ptr_map[index];
    if (entry->ptr == NULL) {
      break;
    }
    if (entry->ptr == ptr) {
      entry->bucket->live_count--;
      entry->bucket->live_bytes -= entry->size;
      entry->ptr = QJS_AT_PTR_TOMBSTONE;
      return;
    }
    index = (index + 1) & (QJS_AT_PTR_MAP_SIZE - 1);
  }
  /* allocated before tracing started or dropped from the map */
  qjs_at_unmatched_frees++;
}

static int qjs_at_stack_equals(const QjsAtBucket *bucket, int n_js,
                               const uint32_t *js_ids, int n_native,
                               const uintptr_t *native_pcs) {
  return bucket->n_js == n_js && bucket->n_native == n_native &&
         memcmp(&qjs_at_id_arena[bucket->js_off], js_ids,
                (size_t)n_js * sizeof(uint32_t)) == 0 &&
         memcmp(bucket->native_pcs, native_pcs,
                (size_t)n_native * sizeof(uintptr_t)) == 0;
}

static void qjs_at_record_aggregate(int kind, const void *ptr, const void *ptr2,
                                    size_t size,
                                    const QjsAtJsFrame *js_frames, int n_js_frames) {
  uintptr_t native_pcs[QJS_AT_MAX_NATIVE_FRAMES];
  uint32_t js_ids[QJS_AT_MAX_JS_FRAMES];
  int n_native;
  uint64_t hash;
  size_t index;

  for (int i = 0; i < n_js_frames; i++) {
    js_ids[i] = qjs_at_intern_frame(&js_frames[i]);
    if (js_ids[i] == UINT32_MAX) {
      qjs_at_dropped++;
      return;
    }
  }
  n_native = qjs_at_capture_native(native_pcs, QJS_AT_MAX_NATIVE_FRAMES);
  hash = qjs_at_hash_stack(n_js_frames, js_ids, n_native, native_pcs);
  index = (size_t)(hash % QJS_AT_BUCKETS);
  for (size_t probe = 0; probe < QJS_AT_BUCKETS; probe++) {
    QjsAtBucket *bucket = &qjs_at_buckets[index];
    if (!bucket->occupied) {
      if (qjs_at_id_arena_used + n_js_frames > QJS_AT_ID_ARENA) {
        qjs_at_dropped++;
        return;
      }
      memset(bucket, 0, sizeof(*bucket));
      bucket->occupied = 1;
      bucket->js_off = (uint32_t)qjs_at_id_arena_used;
      bucket->n_js = (uint8_t)n_js_frames;
      bucket->n_native = (uint8_t)n_native;
      memcpy(&qjs_at_id_arena[qjs_at_id_arena_used], js_ids,
             (size_t)n_js_frames * sizeof(uint32_t));
      qjs_at_id_arena_used += n_js_frames;
      memcpy(bucket->native_pcs, native_pcs,
             (size_t)n_native * sizeof(uintptr_t));
    } else if (!qjs_at_stack_equals(bucket, n_js_frames, js_ids, n_native,
                                    native_pcs)) {
      index = (index + 1) % QJS_AT_BUCKETS;
      continue;
    }
    if (kind == QJS_AT_ALLOC) {
      bucket->alloc_count++;
      bucket->alloc_bytes += size;
      if (qjs_at_ptr_map && qjs_at_sample_rate == 1) {
        bucket->live_count++;
        bucket->live_bytes += size;
        qjs_at_ptr_insert(ptr, bucket, size);
      }
    } else if (kind == QJS_AT_FREE) {
      bucket->free_count++;
      bucket->free_bytes += size;
      if (qjs_at_ptr_map && qjs_at_sample_rate == 1) {
        qjs_at_ptr_remove(ptr);
      }
    } else if (kind == QJS_AT_REALLOC) {
      bucket->realloc_count++;
      if (qjs_at_ptr_map && qjs_at_sample_rate == 1) {
        qjs_at_ptr_remove(ptr2);
        bucket->live_count++;
        bucket->live_bytes += size;
        qjs_at_ptr_insert(ptr, bucket, size);
      }
    }
    return;
  }
  qjs_at_dropped++;
}

void qjs_at_record(int kind, const void *ptr, const void *ptr2, size_t size,
                   const QjsAtJsFrame *js_frames, int n_js_frames) {
  static const QjsAtJsFrame empty_frame;
  if (!qjs_at_enabled) {
    return;
  }
  if (n_js_frames > QJS_AT_MAX_JS_FRAMES) {
    n_js_frames = QJS_AT_MAX_JS_FRAMES;
  }
  if (n_js_frames < 0) {
    n_js_frames = 0;
  }
  if (!js_frames) {
    js_frames = &empty_frame;
    n_js_frames = 0;
  }

  pthread_mutex_lock(&qjs_at_mutex);
  if (!qjs_at_enabled) {
    pthread_mutex_unlock(&qjs_at_mutex);
    return;
  }

  if (kind == QJS_AT_ALLOC) {
    qjs_at_alloc_count++;
    qjs_at_alloc_bytes += size;
  } else if (kind == QJS_AT_FREE) {
    qjs_at_free_count++;
    qjs_at_free_bytes += size;
  } else if (kind == QJS_AT_REALLOC) {
    qjs_at_realloc_count++;
  }

  if (qjs_at_mode == QJS_AT_MODE_AGGREGATE && qjs_at_buckets) {
    qjs_at_record_aggregate(kind, ptr, ptr2, size, js_frames, n_js_frames);
    pthread_mutex_unlock(&qjs_at_mutex);
    return;
  }

  if (qjs_at_mode == QJS_AT_MODE_RAW && qjs_at_events) {
    QjsAtEvent *event = &qjs_at_events[qjs_at_write];
    if (qjs_at_count == QJS_AT_CAPACITY) {
      qjs_at_dropped++;
    } else {
      qjs_at_count++;
    }
    qjs_at_write = (qjs_at_write + 1) % QJS_AT_CAPACITY;

    memset(event, 0, sizeof(*event));
    event->seq = qjs_at_next_seq++;
    event->kind = (uint8_t)kind;
    event->size = (uint64_t)size;
    event->ptr = ptr;
    event->ptr2 = ptr2;
    if (n_js_frames > 0) {
      memcpy(event->js_frames, js_frames, n_js_frames * sizeof(QjsAtJsFrame));
      event->n_js = (uint8_t)n_js_frames;
    }
    event->n_native =
        (uint8_t)qjs_at_capture_native(event->native_pcs, QJS_AT_MAX_NATIVE_FRAMES);
  }
  pthread_mutex_unlock(&qjs_at_mutex);
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

static void qjs_at_print_js_stack(FILE *out, int n_js, const QjsAtJsFrame *frames) {
  for (int i = 0; i < n_js; i++) {
    const QjsAtJsFrame *frame = &frames[i];
    if (i > 0) {
      fputc(';', out);
    }
    if (frame->is_native) {
      fprintf(out, "<native>");
    } else {
      qjs_at_print_name(out, frame->func_name[0] ? frame->func_name : "<anonymous>");
      fputc('@', out);
      qjs_at_print_name(out, frame->filename[0] ? frame->filename : "?");
      if (frame->line_num) {
        fprintf(out, ":%u", frame->line_num);
      }
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

static void qjs_at_dump_event(FILE *out, const QjsAtEvent *event) {
  char kind = '?';
  if (event->kind == QJS_AT_ALLOC) {
    kind = 'A';
  } else if (event->kind == QJS_AT_FREE) {
    kind = 'F';
  } else if (event->kind == QJS_AT_REALLOC) {
    kind = 'R';
  }
  fprintf(out, "%c %llu %p", kind, (unsigned long long)event->seq, event->ptr);
  if (event->kind == QJS_AT_REALLOC) {
    fprintf(out, " old=%p", event->ptr2);
  }
  fprintf(out, " size=%llu js=", (unsigned long long)event->size);
  qjs_at_print_js_stack(out, event->n_js, event->js_frames);
  fprintf(out, " native=");
  qjs_at_print_native_stack(out, event->n_native, event->native_pcs);
  fputc('\n', out);
}

static int qjs_at_compare_buckets(const void *a, const void *b) {
  const QjsAtBucket *ba = *(QjsAtBucket *const *)a;
  const QjsAtBucket *bb = *(QjsAtBucket *const *)b;
  if (ba->alloc_bytes != bb->alloc_bytes) {
    return ba->alloc_bytes < bb->alloc_bytes ? 1 : -1;
  }
  if (ba->alloc_count != bb->alloc_count) {
    return ba->alloc_count < bb->alloc_count ? 1 : -1;
  }
  return 0;
}

static void qjs_at_dump_aggregate(FILE *out) {
  static QjsAtBucket *sorted[QJS_AT_BUCKETS];
  size_t n = 0;
  for (size_t i = 0; i < QJS_AT_BUCKETS; i++) {
    if (qjs_at_buckets[i].occupied) {
      sorted[n++] = &qjs_at_buckets[i];
    }
  }
  qsort(sorted, n, sizeof(QjsAtBucket *), qjs_at_compare_buckets);
  fprintf(out, "# aggregate buckets=%zu\n", n);
  if (n > QJS_AT_DUMP_MAX_BUCKETS) {
    fprintf(out, "# truncated to top %d buckets by alloc_bytes\n",
            QJS_AT_DUMP_MAX_BUCKETS);
    n = QJS_AT_DUMP_MAX_BUCKETS;
  }
  for (size_t i = 0; i < n; i++) {
    const QjsAtBucket *bucket = sorted[i];
    QjsAtJsFrame frames[QJS_AT_MAX_JS_FRAMES];
    for (int j = 0; j < bucket->n_js; j++) {
      frames[j] = qjs_at_frame_arena[qjs_at_id_arena[bucket->js_off + j]];
    }
    fprintf(out, "S allocs=%llu alloc_bytes=%llu frees=%llu free_bytes=%llu reallocs=%llu js=",
            (unsigned long long)bucket->alloc_count,
            (unsigned long long)bucket->alloc_bytes,
            (unsigned long long)bucket->free_count,
            (unsigned long long)bucket->free_bytes,
            (unsigned long long)bucket->realloc_count);
    qjs_at_print_js_stack(out, bucket->n_js, frames);
    fprintf(out, " native=");
    qjs_at_print_native_stack(out, bucket->n_native, bucket->native_pcs);
    fputc('\n', out);
  }
}

int qjs_at_dump(const char *path) {
  FILE *out = fopen(path, "w");
  if (!out) {
    return -1;
  }
  pthread_mutex_lock(&qjs_at_mutex);
  fprintf(out, "# qjs-alloc-trace v1 mode=%d sample_rate=%u count=%zu dropped=%llu base=0x%llx\n",
          qjs_at_mode, qjs_at_sample_rate, qjs_at_count, (unsigned long long)qjs_at_dropped,
          (unsigned long long)qjs_at_base);
  fprintf(out, "# totals allocs=%llu alloc_bytes=%llu frees=%llu free_bytes=%llu reallocs=%llu\n",
          (unsigned long long)qjs_at_alloc_count,
          (unsigned long long)qjs_at_alloc_bytes,
          (unsigned long long)qjs_at_free_count,
          (unsigned long long)qjs_at_free_bytes,
          (unsigned long long)qjs_at_realloc_count);
  fprintf(out, "# arena unique_frames=%zu/%d frame_ids=%zu/%d\n",
          qjs_at_frame_arena_used, QJS_AT_FRAME_ARENA,
          qjs_at_id_arena_used, QJS_AT_ID_ARENA);
  if (qjs_at_mode == QJS_AT_MODE_AGGREGATE && qjs_at_buckets) {
    qjs_at_dump_aggregate(out);
  } else if (qjs_at_mode == QJS_AT_MODE_RAW && qjs_at_events && qjs_at_count > 0) {
    size_t start = qjs_at_count == QJS_AT_CAPACITY ? qjs_at_write : 0;
    for (size_t i = 0; i < qjs_at_count; i++) {
      size_t index = (start + i) % QJS_AT_CAPACITY;
      qjs_at_dump_event(out, &qjs_at_events[index]);
    }
  }
  pthread_mutex_unlock(&qjs_at_mutex);
  fflush(out);
#if QJS_AT_HAVE_UNWIND
  fsync(fileno(out));
#endif
  fclose(out);
  return 0;
}

static int qjs_at_compare_buckets_live(const void *a, const void *b) {
  const QjsAtBucket *ba = *(QjsAtBucket *const *)a;
  const QjsAtBucket *bb = *(QjsAtBucket *const *)b;
  if (ba->live_bytes != bb->live_bytes) {
    return ba->live_bytes < bb->live_bytes ? 1 : -1;
  }
  if (ba->live_count != bb->live_count) {
    return ba->live_count < bb->live_count ? 1 : -1;
  }
  return 0;
}

int qjs_at_dump_heap(const char *path) {
  FILE *out = fopen(path, "w");
  if (!out) {
    return -1;
  }
  pthread_mutex_lock(&qjs_at_mutex);
  uint64_t total_live_count = 0;
  uint64_t total_live_bytes = 0;
  fprintf(out, "# qjs-alloc-trace v1 mode=heap sample_rate=%u unmatched_frees=%llu ptr_dropped=%llu base=0x%llx\n",
          qjs_at_sample_rate, (unsigned long long)qjs_at_unmatched_frees,
          (unsigned long long)qjs_at_ptr_dropped,
          (unsigned long long)qjs_at_base);
  if (qjs_at_mode == QJS_AT_MODE_AGGREGATE && qjs_at_buckets) {
    static QjsAtBucket *sorted[QJS_AT_BUCKETS];
    size_t n = 0;
    for (size_t i = 0; i < QJS_AT_BUCKETS; i++) {
      if (qjs_at_buckets[i].occupied && qjs_at_buckets[i].live_count > 0) {
        total_live_count += qjs_at_buckets[i].live_count;
        total_live_bytes += qjs_at_buckets[i].live_bytes;
        sorted[n++] = &qjs_at_buckets[i];
      }
    }
    qsort(sorted, n, sizeof(QjsAtBucket *), qjs_at_compare_buckets_live);
    fprintf(out, "# live objects=%llu live_bytes=%llu buckets=%zu\n",
            (unsigned long long)total_live_count,
            (unsigned long long)total_live_bytes, n);
    if (n > QJS_AT_DUMP_MAX_BUCKETS) {
      fprintf(out, "# truncated to top %d buckets by live_bytes\n",
              QJS_AT_DUMP_MAX_BUCKETS);
      n = QJS_AT_DUMP_MAX_BUCKETS;
    }
    for (size_t i = 0; i < n; i++) {
      const QjsAtBucket *bucket = sorted[i];
      QjsAtJsFrame frames[QJS_AT_MAX_JS_FRAMES];
      for (int j = 0; j < bucket->n_js; j++) {
        frames[j] = qjs_at_frame_arena[qjs_at_id_arena[bucket->js_off + j]];
      }
      fprintf(out, "S allocs=%llu alloc_bytes=%llu frees=0 free_bytes=0 reallocs=0 js=",
              (unsigned long long)bucket->live_count,
              (unsigned long long)bucket->live_bytes);
      qjs_at_print_js_stack(out, bucket->n_js, frames);
      fprintf(out, " native=");
      qjs_at_print_native_stack(out, bucket->n_native, bucket->native_pcs);
      fputc('\n', out);
    }
  }
  pthread_mutex_unlock(&qjs_at_mutex);
  fflush(out);
#if QJS_AT_HAVE_UNWIND
  fsync(fileno(out));
#endif
  fclose(out);
  return 0;
}
