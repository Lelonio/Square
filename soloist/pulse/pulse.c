/*
 * A minimal libpulse.so.0 for Spotify Soloist on Android.
 *
 * Soloist dlopens libpulse and plays through the threaded-mainloop and stream
 * API. There is no PulseAudio on Android, and no daemon is wanted: this
 * library implements just the calls Soloist makes, keeps what it writes in a
 * ring buffer, and a drain thread hands the PCM on at the pace it would play.
 *
 * Where the PCM goes:
 *   SQUARE_PCM_FD=<n>    an inherited descriptor (a pipe Square reads), which
 *                        then paces the drain by blocking;
 *   SQUARE_PCM_PATH=<p>  a file, paced by the clock (for the probe);
 *   SQUARE_PCM_FIFO=<p>  a FIFO Square reads, in packets (see send()), with
 *                        the audio converted to 16-bit; paced by the reader;
 *   neither              dropped, paced by the clock.
 * Each run of PCM that starts after a change of format is preceded by a line
 * "SQPCM <format> <rate> <channels>\n" on stderr.
 *
 * Built against glibc (aarch64-linux-gnu) with no system headers: the few
 * libc declarations needed are below, sized for glibc's aarch64 ABI.
 */

typedef unsigned long size_t;
typedef long ssize_t;
typedef unsigned char uint8_t;
typedef unsigned int uint32_t;
typedef int int32_t;
typedef unsigned long uint64_t;
typedef long int64_t;
typedef unsigned long pthread_t;

#define NULL ((void *)0)

/* ------------------------------------------------------------------ libc */

struct timeval { long tv_sec; long tv_usec; };
struct timespec { long tv_sec; long tv_nsec; };

typedef struct { _Alignas(8) unsigned char b[64]; } mutex_t;
typedef struct { _Alignas(8) unsigned char b[64]; } cond_t;
typedef struct { _Alignas(8) unsigned char b[16]; } mutexattr_t;

extern void *malloc(size_t);
extern void *calloc(size_t, size_t);
extern void *realloc(void *, size_t);
extern void free(void *);
extern void *memcpy(void *, const void *, size_t);
extern void *memset(void *, int, size_t);
extern char *strdup(const char *);
extern char *getenv(const char *);
extern int access(const char *, int);
extern ssize_t read(int, void *, size_t);
extern long strtol(const char *, char **, int);
extern int open(const char *, int, ...);
extern ssize_t write(int, const void *, size_t);
extern int close(int);
extern int dprintf(int, const char *, ...);
extern int gettimeofday(struct timeval *, void *);
extern int clock_gettime(int, struct timespec *);
extern int nanosleep(const struct timespec *, struct timespec *);
extern int pthread_create(pthread_t *, const void *, void *(*)(void *), void *);
extern int pthread_join(pthread_t, void **);
extern int pthread_mutex_init(mutex_t *, const mutexattr_t *);
extern int pthread_mutex_lock(mutex_t *);
extern int pthread_mutex_unlock(mutex_t *);
extern int pthread_mutex_destroy(mutex_t *);
extern int pthread_mutexattr_init(mutexattr_t *);
extern int pthread_mutexattr_settype(mutexattr_t *, int);
extern int pthread_cond_init(cond_t *, const void *);
extern int pthread_cond_wait(cond_t *, mutex_t *);
extern int pthread_cond_timedwait(cond_t *, mutex_t *, const struct timespec *);
extern int pthread_cond_broadcast(cond_t *);
extern int pthread_cond_destroy(cond_t *);

#define PTHREAD_MUTEX_RECURSIVE 1
#define CLOCK_REALTIME 0
#define CLOCK_MONOTONIC 1
#define O_WRONLY 01
#define O_CREAT 0100
#define O_TRUNC 01000
#define EXPORT __attribute__((visibility("default")))

static uint64_t now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000ul + (uint64_t)t.tv_nsec / 1000ul;
}

static void sleep_us(uint64_t us) {
    struct timespec t = { (long)(us / 1000000ul), (long)(us % 1000000ul) * 1000l };
    nanosleep(&t, NULL);
}

/* ------------------------------------------------------------- to Square */

/* One FIFO for the life of the process, shared by every stream Soloist opens:
 * Square reads it from start to end. Each packet is "SQPC", a type, three
 * bytes of padding and a little-endian length, then the payload:
 *   'H' format: u32 rate, u32 channels   (a stream begins)
 *   'A' audio:  interleaved signed 16-bit PCM
 *   'P' pause   'R' resume   'F' flush   (no payload)
 *   'N' the new song is heard: the first of it after a song was kept to
 *       dissolve, so the player can show it then and not at the skip */
static int fifo_fd = -2;
static mutex_t fifo_lock;
static int fifo_lock_ready;

static int fifo(void) {
    if (fifo_fd == -2) {
        const char *p = getenv("SQUARE_PCM_FIFO");
        fifo_fd = (p && *p) ? open(p, O_WRONLY) : -1;
    }
    return fifo_fd;
}

static void send(char type, const void *data, uint32_t n) {
    int fd = fifo();
    if (fd < 0) return;
    if (!fifo_lock_ready) { pthread_mutex_init(&fifo_lock, NULL); fifo_lock_ready = 1; }
    unsigned char h[12] = { 'S', 'Q', 'P', 'C', (unsigned char)type, 0, 0, 0,
                            (unsigned char)n, (unsigned char)(n >> 8), (unsigned char)(n >> 16), (unsigned char)(n >> 24) };
    pthread_mutex_lock(&fifo_lock);
    size_t off = 0;
    while (off < sizeof h) { ssize_t w = write(fd, h + off, sizeof h - off); if (w <= 0) goto done; off += (size_t)w; }
    off = 0;
    while (off < n) { ssize_t w = write(fd, (const unsigned char *)data + off, n - off); if (w <= 0) goto done; off += (size_t)w; }
done:
    pthread_mutex_unlock(&fifo_lock);
}

static int skipping(void);

/* The song being left, kept to dissolve into the next one. Not the stream's:
 * Soloist closes its stream and opens another for a new context, and the
 * dissolve has to carry across. */
static unsigned char *g_tail;
static size_t g_tail_len, g_tail_pos;
static uint64_t g_tail_since;
/* The tail came from a stream that closed: there is no old song left to wait
 * on, so it plays on its own at once. */
static int g_tail_closed;
/* A tail was kept and the song after it has not been heard yet. */
static int g_wait_new;

static void drop_tail(void) {
    free(g_tail);
    g_tail = NULL;
    g_tail_len = g_tail_pos = 0;
}
typedef struct pa_stream pa_stream_fwd;

/* ------------------------------------------------------------ pulse ABI */

typedef enum { PA_SAMPLE_U8, PA_SAMPLE_ALAW, PA_SAMPLE_ULAW, PA_SAMPLE_S16LE, PA_SAMPLE_S16BE,
               PA_SAMPLE_FLOAT32LE, PA_SAMPLE_FLOAT32BE, PA_SAMPLE_S32LE, PA_SAMPLE_S32BE,
               PA_SAMPLE_S24LE, PA_SAMPLE_S24BE, PA_SAMPLE_S24_32LE, PA_SAMPLE_S24_32BE } pa_sample_format_t;

typedef struct { pa_sample_format_t format; uint32_t rate; uint8_t channels; } pa_sample_spec;
typedef struct { uint8_t channels; int map[32]; } pa_channel_map;
typedef uint32_t pa_volume_t;
typedef struct { uint8_t channels; pa_volume_t values[32]; } pa_cvolume;
typedef uint64_t pa_usec_t;

#define PA_VOLUME_NORM 0x10000u

typedef struct {
    struct timeval timestamp;
    int synchronized_clocks;
    pa_usec_t sink_usec;
    pa_usec_t source_usec;
    pa_usec_t transport_usec;
    int playing;
    int write_index_corrupt;
    int64_t write_index;
    int read_index_corrupt;
    int64_t read_index;
    pa_usec_t configured_sink_usec;
    pa_usec_t configured_source_usec;
    uint64_t since_underrun;
} pa_timing_info;

typedef struct pa_proplist pa_proplist;
typedef struct pa_format_info pa_format_info;

typedef struct {
    uint32_t index;
    const char *name;
    uint32_t owner_module;
    uint32_t client;
    uint32_t sink;
    pa_sample_spec sample_spec;
    pa_channel_map channel_map;
    pa_cvolume volume;
    pa_usec_t buffer_usec;
    pa_usec_t sink_usec;
    const char *resample_method;
    const char *driver;
    int mute;
    pa_proplist *proplist;
    int corked;
    int has_volume;
    int volume_writable;
    pa_format_info *format;
} pa_sink_input_info;

enum { PA_CONTEXT_UNCONNECTED, PA_CONTEXT_CONNECTING, PA_CONTEXT_AUTHORIZING,
       PA_CONTEXT_SETTING_NAME, PA_CONTEXT_READY, PA_CONTEXT_FAILED, PA_CONTEXT_TERMINATED };
enum { PA_STREAM_UNCONNECTED, PA_STREAM_CREATING, PA_STREAM_READY, PA_STREAM_FAILED,
       PA_STREAM_TERMINATED };
enum { PA_OPERATION_RUNNING, PA_OPERATION_DONE, PA_OPERATION_CANCELLED };

typedef struct pa_threaded_mainloop pa_threaded_mainloop;
typedef struct pa_context pa_context;
typedef struct pa_stream pa_stream;
typedef struct pa_operation { int refs; } pa_operation;
typedef struct pa_mainloop_api { void *userdata; } pa_mainloop_api;

typedef void (*pa_context_notify_cb_t)(pa_context *, void *);
typedef void (*pa_context_success_cb_t)(pa_context *, int, void *);
typedef void (*pa_context_subscribe_cb_t)(pa_context *, int, uint32_t, void *);
typedef void (*pa_sink_input_info_cb_t)(pa_context *, const pa_sink_input_info *, int, void *);
typedef void (*pa_stream_notify_cb_t)(pa_stream *, void *);
typedef void (*pa_stream_success_cb_t)(pa_stream *, int, void *);
typedef void (*pa_free_cb_t)(void *);

/* --------------------------------------------------------------- objects */

/* One deferred callback, run on the mainloop thread with the lock held, as
 * PulseAudio runs every callback. */
typedef struct job {
    struct job *next;
    void (*fn)(void *a, void *b, void *c);
    void *a, *b, *c;
} job;

struct pa_threaded_mainloop {
    mutex_t lock;
    cond_t cond;
    pthread_t thread;
    int running;
    int stopping;
    job *jobs;
    pa_mainloop_api api;
};

struct pa_context {
    pa_threaded_mainloop *loop;
    int state;
    pa_context_notify_cb_t state_cb;
    void *state_ud;
    pa_stream *stream;
};

#define RING_MS 1500

struct pa_stream {
    pa_context *ctx;
    int state;
    pa_sample_spec spec;
    size_t frame;
    pa_stream_notify_cb_t state_cb; void *state_ud;
    pa_stream_notify_cb_t started_cb; void *started_ud;
    pa_stream_notify_cb_t underflow_cb; void *underflow_ud;
    int corked;
    int started;
    pa_cvolume volume;
    /* ring */
    unsigned char *ring;
    size_t cap, head, used;
    size_t target; /* what is offered as writable, whatever the ring has grown to */
    /* The song being left, kept from a flush to dissolve into the next one
     * (SQUARE_XFADE_MS): the part of it that was decoded and not yet heard. */
    unsigned char *tail;
    size_t tail_len, tail_pos;
    uint64_t tail_since;
    /* Corked by Soloist for a change of song Square asked for: Soloist is told
     * it is corked, the music keeps going from the ring until the new song is
     * there to dissolve into. */
    int held;
    uint64_t held_since;
    int64_t written, played;
    pa_timing_info timing;
    /* drain */
    pthread_t drain;
    int draining;
    int fd;
    int clocked;
    int to_square;
};

static pa_operation *op_new(void) {
    pa_operation *o = calloc(1, sizeof *o);
    o->refs = 1;
    return o;
}

static void post(pa_threaded_mainloop *m, void (*fn)(void *, void *, void *), void *a, void *b, void *c) {
    job *j = calloc(1, sizeof *j);
    j->fn = fn; j->a = a; j->b = b; j->c = c;
    pthread_mutex_lock(&m->lock);
    job **tail = &m->jobs;
    while (*tail) tail = &(*tail)->next;
    *tail = j;
    pthread_mutex_unlock(&m->lock);
}

static void *loop_main(void *arg) {
    pa_threaded_mainloop *m = arg;
    pthread_mutex_lock(&m->lock);
    while (!m->stopping) {
        while (m->jobs) {
            job *j = m->jobs;
            m->jobs = j->next;
            j->fn(j->a, j->b, j->c);
            free(j);
        }
        /* Wakes anyone in pa_threaded_mainloop_wait: callers loop on their
         * own condition, so a wake with nothing new is harmless, and it is
         * what lets a writer polling writable_size see the room the drain
         * made. */
        pthread_cond_broadcast(&m->cond);
        pthread_mutex_unlock(&m->lock);
        sleep_us(5000);
        pthread_mutex_lock(&m->lock);
    }
    pthread_mutex_unlock(&m->lock);
    return NULL;
}

/* ------------------------------------------------------- threaded mainloop */

EXPORT pa_threaded_mainloop *pa_threaded_mainloop_new(void) {
    pa_threaded_mainloop *m = calloc(1, sizeof *m);
    mutexattr_t a;
    pthread_mutexattr_init(&a);
    pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&m->lock, &a);
    pthread_cond_init(&m->cond, NULL);
    m->api.userdata = m;
    return m;
}

EXPORT int pa_threaded_mainloop_start(pa_threaded_mainloop *m) {
    if (m->running) return 0;
    m->stopping = 0;
    m->running = pthread_create(&m->thread, NULL, loop_main, m) == 0;
    return m->running ? 0 : -1;
}

EXPORT void pa_threaded_mainloop_stop(pa_threaded_mainloop *m) {
    if (!m->running) return;
    pthread_mutex_lock(&m->lock);
    m->stopping = 1;
    pthread_mutex_unlock(&m->lock);
    pthread_join(m->thread, NULL);
    m->running = 0;
}

EXPORT void pa_threaded_mainloop_free(pa_threaded_mainloop *m) {
    pa_threaded_mainloop_stop(m);
    while (m->jobs) { job *j = m->jobs; m->jobs = j->next; free(j); }
    pthread_cond_destroy(&m->cond);
    pthread_mutex_destroy(&m->lock);
    free(m);
}

EXPORT void pa_threaded_mainloop_lock(pa_threaded_mainloop *m) { pthread_mutex_lock(&m->lock); }
EXPORT void pa_threaded_mainloop_unlock(pa_threaded_mainloop *m) { pthread_mutex_unlock(&m->lock); }
EXPORT void pa_threaded_mainloop_signal(pa_threaded_mainloop *m, int wait_for_accept) {
    (void)wait_for_accept;
    pthread_cond_broadcast(&m->cond);
}

EXPORT void pa_threaded_mainloop_wait(pa_threaded_mainloop *m) {
    struct timespec t;
    clock_gettime(CLOCK_REALTIME, &t);
    t.tv_nsec += 20 * 1000000l;
    if (t.tv_nsec >= 1000000000l) { t.tv_sec++; t.tv_nsec -= 1000000000l; }
    pthread_cond_timedwait(&m->cond, &m->lock, &t);
}

EXPORT pa_mainloop_api *pa_threaded_mainloop_get_api(pa_threaded_mainloop *m) { return &m->api; }

/* ---------------------------------------------------------------- context */

static void ctx_set_state(void *a, void *b, void *c) {
    (void)c;
    pa_context *ctx = a;
    ctx->state = (int)(long)b;
    if (ctx->state_cb) ctx->state_cb(ctx, ctx->state_ud);
}

EXPORT pa_context *pa_context_new(pa_mainloop_api *api, const char *name) {
    (void)name;
    pa_context *c = calloc(1, sizeof *c);
    c->loop = api->userdata;
    c->state = PA_CONTEXT_UNCONNECTED;
    return c;
}

EXPORT void pa_context_set_state_callback(pa_context *c, pa_context_notify_cb_t cb, void *ud) {
    c->state_cb = cb; c->state_ud = ud;
}

EXPORT int pa_context_connect(pa_context *c, const char *server, int flags, const void *spawn_api) {
    (void)server; (void)flags; (void)spawn_api;
    /* CONNECTING at once, as the real library: a caller reading the state
     * right after this call treats anything else as a failure. */
    c->state = PA_CONTEXT_CONNECTING;
    if (c->state_cb) c->state_cb(c, c->state_ud);
    post(c->loop, ctx_set_state, c, (void *)(long)PA_CONTEXT_READY, NULL);
    return 0;
}

EXPORT void pa_context_disconnect(pa_context *c) {
    c->state = PA_CONTEXT_TERMINATED;
    if (c->state_cb) c->state_cb(c, c->state_ud);
}

EXPORT int pa_context_get_state(const pa_context *c) { return c->state; }
EXPORT int pa_context_errno(const pa_context *c) { (void)c; return 0; }
EXPORT void pa_context_unref(pa_context *c) { free(c); }

EXPORT void pa_context_set_subscribe_callback(pa_context *c, pa_context_subscribe_cb_t cb, void *ud) {
    (void)c; (void)cb; (void)ud;
}

static void ctx_success(void *a, void *b, void *c) {
    pa_context_success_cb_t cb = (pa_context_success_cb_t)b;
    if (cb) cb(a, 1, c);
}

EXPORT pa_operation *pa_context_subscribe(pa_context *c, int mask, pa_context_success_cb_t cb, void *ud) {
    (void)mask;
    post(c->loop, ctx_success, c, (void *)cb, ud);
    return op_new();
}

typedef struct { pa_sink_input_info_cb_t cb; void *ud; } info_req;

static void ctx_sink_input_info(void *a, void *b, void *c) {
    (void)c;
    pa_context *ctx = a;
    info_req *r = b;
    pa_stream *s = ctx->stream;
    if (s) {
        pa_sink_input_info info;
        memset(&info, 0, sizeof info);
        info.index = 0;
        info.name = "Soloist";
        info.sample_spec = s->spec;
        info.channel_map.channels = s->spec.channels;
        info.volume = s->volume;
        info.corked = s->corked;
        info.has_volume = 1;
        info.volume_writable = 1;
        r->cb(ctx, &info, 0, r->ud);
    }
    r->cb(ctx, NULL, 1, r->ud);
    free(r);
}

EXPORT pa_operation *pa_context_get_sink_input_info(pa_context *c, uint32_t idx, pa_sink_input_info_cb_t cb, void *ud) {
    (void)idx;
    if (cb) {
        info_req *r = calloc(1, sizeof *r);
        r->cb = cb; r->ud = ud;
        post(c->loop, ctx_sink_input_info, c, r, NULL);
    }
    return op_new();
}

EXPORT pa_operation *pa_context_set_sink_input_volume(pa_context *c, uint32_t idx, const pa_cvolume *v,
                                                      pa_context_success_cb_t cb, void *ud) {
    (void)idx;
    if (c->stream && v) c->stream->volume = *v;
    post(c->loop, ctx_success, c, (void *)cb, ud);
    return op_new();
}

/* ---------------------------------------------------------------- volume */

EXPORT pa_volume_t pa_cvolume_avg(const pa_cvolume *v) {
    if (!v || !v->channels) return 0;
    uint64_t sum = 0;
    for (int i = 0; i < v->channels; i++) sum += v->values[i];
    return (pa_volume_t)(sum / v->channels);
}

EXPORT pa_cvolume *pa_cvolume_set(pa_cvolume *v, unsigned channels, pa_volume_t value) {
    if (channels > 32) channels = 32;
    v->channels = (uint8_t)channels;
    for (unsigned i = 0; i < channels; i++) v->values[i] = value;
    return v;
}

/* --------------------------------------------------------------- proplist */

EXPORT pa_proplist *pa_proplist_new(void) { return calloc(1, 8); }
EXPORT void pa_proplist_free(pa_proplist *p) { free(p); }
EXPORT int pa_proplist_sets(pa_proplist *p, const char *k, const char *v) { (void)p; (void)k; (void)v; return 0; }

/* ------------------------------------------------------------------ misc */

EXPORT const char *pa_strerror(int e) { (void)e; return "error"; }
EXPORT int pa_operation_get_state(const pa_operation *o) { (void)o; return PA_OPERATION_DONE; }
EXPORT void pa_operation_unref(pa_operation *o) { free(o); }

EXPORT struct timeval *pa_gettimeofday(struct timeval *tv) { gettimeofday(tv, NULL); return tv; }

EXPORT pa_usec_t pa_timeval_diff(const struct timeval *a, const struct timeval *b) {
    int64_t d = ((int64_t)a->tv_sec - b->tv_sec) * 1000000l + (a->tv_usec - b->tv_usec);
    return (pa_usec_t)(d < 0 ? -d : d);
}

static size_t frame_size(const pa_sample_spec *s) {
    size_t bytes;
    switch (s->format) {
        case PA_SAMPLE_U8: case PA_SAMPLE_ALAW: case PA_SAMPLE_ULAW: bytes = 1; break;
        case PA_SAMPLE_S16LE: case PA_SAMPLE_S16BE: bytes = 2; break;
        case PA_SAMPLE_S24LE: case PA_SAMPLE_S24BE: bytes = 3; break;
        default: bytes = 4;
    }
    return bytes * (s->channels ? s->channels : 1);
}

EXPORT size_t pa_usec_to_bytes(pa_usec_t t, const pa_sample_spec *s) {
    return (size_t)(t * s->rate / 1000000ul) * frame_size(s);
}

/* ----------------------------------------------------------------- stream */

static void stream_set_state(void *a, void *b, void *c) {
    (void)c;
    pa_stream *s = a;
    s->state = (int)(long)b;
    if (s->state_cb) s->state_cb(s, s->state_ud);
}

static void stream_started(void *a, void *b, void *c) {
    (void)b; (void)c;
    pa_stream *s = a;
    if (s->started_cb) s->started_cb(s, s->started_ud);
}

static void stream_success(void *a, void *b, void *c) {
    pa_stream_success_cb_t cb = (pa_stream_success_cb_t)b;
    if (cb) cb(a, 1, c);
}

EXPORT pa_stream *pa_stream_new_with_proplist(pa_context *c, const char *name, const pa_sample_spec *ss,
                                              const pa_channel_map *map, pa_proplist *p) {
    (void)name; (void)map; (void)p;
    pa_stream *s = calloc(1, sizeof *s);
    s->ctx = c;
    s->spec = *ss;
    s->frame = frame_size(ss);
    pa_cvolume_set(&s->volume, ss->channels, PA_VOLUME_NORM);
    /* Room ahead for the longest dissolve Square may ask for: the rest of a
     * song ending under a crossfade is what is kept in here. */
    const char *most = getenv("SQUARE_XFADE_MAX_MS");
    long ahead = most ? strtol(most, NULL, 10) + 500 : 0;
    if (ahead < RING_MS) ahead = RING_MS;
    s->cap = pa_usec_to_bytes((uint64_t)ahead * 1000ul, ss);
    s->target = s->cap;
    s->ring = malloc(s->cap);
    s->fd = -1;
    c->stream = s;
    dprintf(2, "SQPCM %d %u %u\n", (int)ss->format, ss->rate, (unsigned)ss->channels);
    return s;
}

EXPORT pa_stream *pa_stream_new(pa_context *c, const char *name, const pa_sample_spec *ss, const pa_channel_map *map) {
    return pa_stream_new_with_proplist(c, name, ss, map, NULL);
}

EXPORT void pa_stream_set_state_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *ud) { s->state_cb = cb; s->state_ud = ud; }
EXPORT void pa_stream_set_started_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *ud) { s->started_cb = cb; s->started_ud = ud; }
EXPORT void pa_stream_set_underflow_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *ud) { s->underflow_cb = cb; s->underflow_ud = ud; }
EXPORT int pa_stream_get_state(const pa_stream *s) { return s->state; }
EXPORT uint32_t pa_stream_get_index(const pa_stream *s) { (void)s; return 0; }
EXPORT int pa_stream_is_corked(const pa_stream *s) { return s->corked || s->held; }

/* Scales a block in place by the stream's volume, for the formats Soloist
 * can be expected to ask for. */
static void apply_volume(pa_stream *s, unsigned char *p, size_t n) {
    pa_volume_t v = pa_cvolume_avg(&s->volume);
    if (v >= PA_VOLUME_NORM) return;
    /* PulseAudio's volume is cubic: 0.5 of NORM is about -18 dB. */
    float lin = (float)v / (float)PA_VOLUME_NORM;
    float gain = lin * lin * lin;
    if (s->spec.format == PA_SAMPLE_FLOAT32LE) {
        float *f = (float *)p;
        for (size_t i = 0; i < n / 4; i++) f[i] *= gain;
    } else if (s->spec.format == PA_SAMPLE_S16LE) {
        short *x = (short *)p;
        for (size_t i = 0; i < n / 2; i++) x[i] = (short)(x[i] * gain);
    } else if (s->spec.format == PA_SAMPLE_S32LE) {
        int *x = (int *)p;
        for (size_t i = 0; i < n / 4; i++) x[i] = (int)((double)x[i] * gain);
    }
}

/* To interleaved signed 16-bit, which is what Square's output takes. */
static size_t to_s16(pa_stream *s, const unsigned char *in, size_t n, short *out) {
    size_t count;
    switch (s->spec.format) {
        case PA_SAMPLE_FLOAT32LE: {
            const float *f = (const float *)in;
            count = n / 4;
            for (size_t i = 0; i < count; i++) {
                float v = f[i] * 32767.0f;
                out[i] = (short)(v > 32767.0f ? 32767 : v < -32768.0f ? -32768 : v);
            }
            break;
        }
        case PA_SAMPLE_S32LE: {
            const int *x = (const int *)in;
            count = n / 4;
            for (size_t i = 0; i < count; i++) out[i] = (short)(x[i] >> 16);
            break;
        }
        case PA_SAMPLE_S16LE:
            count = n / 2;
            memcpy(out, in, n);
            break;
        default:
            count = 0;
    }
    return count * 2;
}

static void *drain_main(void *arg) {
    pa_stream *s = arg;
    pa_threaded_mainloop *m = s->ctx->loop;
    size_t chunk = pa_usec_to_bytes(10000, &s->spec);
    unsigned char *buf = malloc(chunk);
    short *pcm16 = malloc(chunk);
    uint64_t next = now_us();
    pthread_mutex_lock(&m->lock);
    while (s->draining) {
        size_t n = 0;
        int heard_new = 0;
        /* Held for a change of song that never came (five seconds: a Connect play can take a couple): it was a pause. */
        if (s->held && now_us() - s->held_since > 5000000) {
            s->held = 0;
            s->corked = 1;
            free(g_tail);
            g_tail = NULL;
            g_tail_len = g_tail_pos = 0;
            if (s->to_square) send('P', NULL, 0);
        }
        if (!s->corked && s->used && !(s->held && g_tail)) {
            n = s->used < chunk ? s->used : chunk;
            n -= n % s->frame;
            for (size_t i = 0; i < n; i++) buf[i] = s->ring[(s->head + i) % s->cap];
            s->head = (s->head + n) % s->cap;
            s->used -= n;
            if (n && g_wait_new) {
                g_wait_new = 0;
                heard_new = 1;
            }
        }
        /* The song being left, dissolving into this one: the old fades out
         * as the new comes in, over what was kept of it. With nothing new
         * yet, it plays on alone a moment, then gives up. */
        if (g_tail && !s->corked) {
            size_t left = g_tail_len - g_tail_pos;
            /* With nothing new yet, the old song goes on fading by itself:
             * at once in a skip (the new one is loading), and after a moment
             * otherwise. */
            int alone = n == 0 && (s->held || g_tail_closed || now_us() - g_tail_since > 250000);
            if (alone) {
                n = left < chunk ? left : chunk;
                memset(buf, 0, n);
            }
            if (n) {
                size_t m = n < left ? n : left;
                float *out = (float *)buf;
                const float *old = (const float *)(g_tail + g_tail_pos);
                size_t frames = m / s->frame, ch = s->spec.channels ? s->spec.channels : 1;
                size_t total = g_tail_len / s->frame, done = g_tail_pos / s->frame;
                for (size_t f = 0; f < frames; f++) {
                    float t = (float)(done + f) / (float)(total ? total : 1);
                    for (size_t c = 0; c < ch; c++) {
                        size_t i = f * ch + c;
                        out[i] = out[i] * t + old[i] * (1.0f - t);
                    }
                }
                g_tail_pos += m;
                if (g_tail_pos >= g_tail_len) {
                    free(g_tail);
                    g_tail = NULL;
                    g_tail_len = g_tail_pos = 0;
                }
            }
        }
        pthread_mutex_unlock(&m->lock);
        if (n) {
            apply_volume(s, buf, n);
            if (s->to_square) {
                size_t m = to_s16(s, buf, n, pcm16);
                if (heard_new) send('N', NULL, 0);
                send('A', pcm16, (uint32_t)m);
            } else if (s->fd >= 0) {
                size_t off = 0;
                while (off < n) {
                    ssize_t w = write(s->fd, buf + off, n - off);
                    if (w <= 0) break;
                    off += (size_t)w;
                }
            }
        }
        pthread_mutex_lock(&m->lock);
        if (n) {
            s->played += (int64_t)n;
            if (!s->started) {
                s->started = 1;
                post(m, stream_started, s, NULL, NULL);
            }
        }
        if (s->clocked || n == 0) {
            pthread_mutex_unlock(&m->lock);
            uint64_t step = n ? (uint64_t)n * 1000000ul / (s->spec.rate * s->frame) : 5000;
            next += step;
            uint64_t t = now_us();
            if (next > t) sleep_us(next - t); else next = t;
            pthread_mutex_lock(&m->lock);
        } else {
            next = now_us();
        }
    }
    pthread_mutex_unlock(&m->lock);
    free(buf);
    free(pcm16);
    return NULL;
}

EXPORT int pa_stream_connect_playback(pa_stream *s, const char *dev, const void *attr, int flags,
                                      const pa_cvolume *volume, pa_stream *sync) {
    (void)dev; (void)attr; (void)sync;
    if (volume) s->volume = *volume;
    s->corked = (flags & 0x1) != 0; /* PA_STREAM_START_CORKED */
    const char *fd = getenv("SQUARE_PCM_FD");
    const char *path = getenv("SQUARE_PCM_PATH");
    if (fifo() >= 0) {
        s->to_square = 1;
        s->clocked = 0;
        uint32_t h[2] = { s->spec.rate, s->spec.channels };
        send('H', h, sizeof h);
        if (!s->corked) send('R', NULL, 0);
    } else if (fd && *fd) {
        s->fd = (int)strtol(fd, NULL, 10);
        s->clocked = 0;
    } else {
        if (path && *path) s->fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        s->clocked = 1;
    }
    s->draining = 1;
    pthread_create(&s->drain, NULL, drain_main, s);
    s->state = PA_STREAM_CREATING;
    if (s->state_cb) s->state_cb(s, s->state_ud);
    post(s->ctx->loop, stream_set_state, s, (void *)(long)PA_STREAM_READY, NULL);
    return 0;
}

static size_t xfade_bytes(pa_stream *s);
static void keep_tail(pa_stream *s);
static long flag_ms(void);

EXPORT int pa_stream_disconnect(pa_stream *s) {
    /* Closed for a new context during a skip: what was ahead of the old song
     * dissolves into the next stream, and Square's output keeps going. */
    int transition = (s->held || skipping()) && xfade_bytes(s);
    if (transition && !g_tail) {
        keep_tail(s);
        g_tail_closed = 1;
    }
    if (s->to_square && !transition) send('P', NULL, 0);
    if (s->draining) {
        s->draining = 0;
        pa_threaded_mainloop *m = s->ctx->loop;
        pthread_mutex_unlock(&m->lock);
        pthread_join(s->drain, NULL);
        pthread_mutex_lock(&m->lock);
    }
    if (s->fd >= 0 && !getenv("SQUARE_PCM_FD")) close(s->fd);
    s->fd = -1;
    s->state = PA_STREAM_TERMINATED;
    if (s->state_cb) s->state_cb(s, s->state_ud);
    return 0;
}

EXPORT void pa_stream_unref(pa_stream *s) {
    if (s->ctx && s->ctx->stream == s) s->ctx->stream = NULL;
    free(s->ring);
    free(s);
}

EXPORT size_t pa_stream_writable_size(const pa_stream *s) {
    size_t room = s->used >= s->target ? 0 : s->target - s->used;
    return room - room % s->frame;
}

EXPORT int pa_stream_write(pa_stream *s, const void *data, size_t n, pa_free_cb_t free_cb, int64_t offset, int seek) {
    (void)offset; (void)seek;
    const unsigned char *p = data;
    /* Never dropped: PulseAudio takes a write past the writable size and
     * plays it, and a byte cut here is a click in the music. The ring grows
     * instead, unrolled into the new buffer. */
    if (n > s->cap - s->used) {
        size_t cap = s->cap * 2;
        while (cap - s->used < n) cap *= 2;
        unsigned char *grown = malloc(cap);
        for (size_t i = 0; i < s->used; i++) grown[i] = s->ring[(s->head + i) % s->cap];
        free(s->ring);
        s->ring = grown;
        s->cap = cap;
        s->head = 0;
    }
    size_t tail = (s->head + s->used) % s->cap;
    for (size_t i = 0; i < n; i++) s->ring[(tail + i) % s->cap] = p[i];
    s->used += n;
    s->written += (int64_t)n;
    if (free_cb) free_cb((void *)data);
    return 0;
}

EXPORT pa_operation *pa_stream_cork(pa_stream *s, int b, pa_stream_success_cb_t cb, void *ud) {
    if (b && !s->corked && !s->held && xfade_bytes(s) && skipping()) {
        /* A skip: the old song plays on, and will be the dissolve's tail. */
        s->held = 1;
        s->held_since = now_us();
        post(s->ctx->loop, stream_success, s, (void *)cb, ud);
        return op_new();
    }
    if (!b && s->held) {
        s->held = 0;
        post(s->ctx->loop, stream_success, s, (void *)cb, ud);
        return op_new();
    }
    if (s->to_square && s->corked != b) send(b ? 'P' : 'R', NULL, 0);
    if (b && g_tail) {
        /* Paused mid-dissolve: the song left behind does not come back. */
        free(g_tail);
        g_tail = NULL;
        g_tail_len = g_tail_pos = 0;
    }
    s->corked = b;
    if (!b) s->started = 0;
    post(s->ctx->loop, stream_success, s, (void *)cb, ud);
    return op_new();
}

/* Square's word that the next cork is a skip, not a pause (SQUARE_XFADE_FLAG,
 * a file it creates just before and removes just after). */
static int skipping(void) {
    const char *p = getenv("SQUARE_XFADE_FLAG");
    return p && *p && access(p, 0) == 0;
}

/* The dissolve's length: what Square wrote in the flag for this change of
 * song (the skip fade, or the crossfade for a song ending), else the
 * default it started us with. */
static long flag_ms(void) {
    const char *p = getenv("SQUARE_XFADE_FLAG");
    if (!p || !*p) return 0;
    int fd = open(p, 0);
    if (fd < 0) return 0;
    char b[24];
    ssize_t n = read(fd, b, sizeof b - 1);
    close(fd);
    if (n <= 0) return 0;
    b[n] = 0;
    return strtol(b, NULL, 10);
}

static size_t xfade_bytes(pa_stream *s) {
    const char *ms = getenv("SQUARE_XFADE_MS");
    long v = flag_ms();
    if (v <= 0) v = ms ? strtol(ms, NULL, 10) : 0;
    if (v <= 0 || s->spec.format != PA_SAMPLE_FLOAT32LE) return 0;
    return pa_usec_to_bytes((uint64_t)v * 1000ul, &s->spec);
}

static void keep_tail(pa_stream *s) {
    size_t want = xfade_bytes(s);
    drop_tail();
    if (!want || s->used == 0) return;
    size_t n = s->used < want ? s->used : want;
    n -= n % s->frame;
    g_tail = malloc(n);
    for (size_t i = 0; i < n; i++) g_tail[i] = s->ring[(s->head + i) % s->cap];
    g_tail_len = n;
    g_tail_since = now_us();
    g_tail_closed = 0;
    g_wait_new = 1;
}

EXPORT pa_operation *pa_stream_flush(pa_stream *s, pa_stream_success_cb_t cb, void *ud) {
    /* A flush while playing is a change of song (or a seek): what was ahead
     * of the old one is kept to dissolve into whatever comes next, rather
     * than dropped, which cut the music at the skip. */
    size_t want = xfade_bytes(s);
    free(g_tail);
    g_tail = NULL;
    g_tail_len = g_tail_pos = 0;
    if (want && (!s->corked || s->held) && s->used > 0) {
        size_t n = s->used < want ? s->used : want;
        n -= n % s->frame;
        g_tail = malloc(n);
        for (size_t i = 0; i < n; i++) g_tail[i] = s->ring[(s->head + i) % s->cap];
        g_tail_len = n;
        g_tail_since = now_us();
        g_wait_new = 1;
    }
    /* What is dropped counts as read, as in PulseAudio, where a flush moves
     * the read index up to the write index. Left behind, the read index lagged
     * the written one by the ring's six seconds more at every seek and skip,
     * and Soloist, which reckons the position from the two, said 0 at a pause
     * well into the song. */
    s->played += (int64_t)s->used;
    s->head = 0;
    s->used = 0;
    if (s->to_square) send('F', NULL, 0);
    post(s->ctx->loop, stream_success, s, (void *)cb, ud);
    return op_new();
}

EXPORT pa_operation *pa_stream_update_timing_info(pa_stream *s, pa_stream_success_cb_t cb, void *ud) {
    post(s->ctx->loop, stream_success, s, (void *)cb, ud);
    return op_new();
}

EXPORT const pa_timing_info *pa_stream_get_timing_info(pa_stream *s) {
    pa_timing_info *t = &s->timing;
    memset(t, 0, sizeof *t);
    gettimeofday(&t->timestamp, NULL);
    t->synchronized_clocks = 1;
    t->playing = !s->corked && s->started;
    t->write_index = s->written;
    t->read_index = s->played;
    return t;
}
