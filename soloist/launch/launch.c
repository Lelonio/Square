/* Starts Soloist on Android for an app targeting API 29 and later.
 *
 * Such an app may not execute, or map as code, any file in its own data
 * directory, and Soloist is downloaded there. It may execute what the APK
 * installed in its native library directory (this launcher and glibc's loader
 * among them) and map anonymous memory files, as ART's JIT does. So the
 * binary is copied into a memfd here, and the loader is run on it in this same
 * process, where the memfd is still open:
 *
 *   libsqlaunch.so <binary> <loader> [loader args...]
 *
 * with "@BIN" among the loader's arguments standing for the memfd's path.
 *
 * glibc's LD_PRELOAD and LD_AUDIT come as SQ_LD_PRELOAD and SQ_LD_AUDIT: under
 * their own names Android's linker, which starts this launcher, would try to
 * load them. They get their names back just before the loader runs. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <binary> <loader> [args...]\n", argv[0]);
        return 2;
    }
    int src = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        fprintf(stderr, "SQLAUNCH cannot open %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    struct stat st;
    if (fstat(src, &st) != 0) return 1;
    /* Not close-on-exec: the loader opens it by its /proc path after exec. */
    int mem = (int)syscall(__NR_memfd_create, "soloist", 0);
    if (mem < 0) {
        fprintf(stderr, "SQLAUNCH memfd_create: %s\n", strerror(errno));
        return 1;
    }
    off_t at = 0;
    while (at < st.st_size) {
        ssize_t n = sendfile(mem, src, &at, (size_t)(st.st_size - at));
        if (n <= 0) {
            fprintf(stderr, "SQLAUNCH copy: %s\n", strerror(errno));
            return 1;
        }
    }
    close(src);
    char path[64];
    snprintf(path, sizeof path, "/proc/self/fd/%d", mem);
    for (int i = 3; i < argc; i++) {
        if (strcmp(argv[i], "@BIN") == 0) argv[i] = path;
    }
    static const char *const handed[] = { "LD_PRELOAD", "LD_AUDIT" };
    for (unsigned i = 0; i < sizeof handed / sizeof *handed; i++) {
        char name[32];
        snprintf(name, sizeof name, "SQ_%s", handed[i]);
        const char *value = getenv(name);
        if (value) {
            setenv(handed[i], value, 1);
            unsetenv(name);
        }
    }
    execv(argv[2], argv + 2);
    fprintf(stderr, "SQLAUNCH exec %s: %s\n", argv[2], strerror(errno));
    return 1;
}
