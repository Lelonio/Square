/* Starts Soloist on Android for an app targeting API 29 and later.
 *
 * Such an app may not execute, or map as code, any file in its own data
 * directory, and Soloist is downloaded there. It may execute what the APK
 * installed in its native library directory (this launcher and glibc's loader
 * among them) and map anonymous memory files as code, as ART's JIT does. So
 * the binary is copied into a memfd here.
 *
 *   libsqlaunch.so <binary> <loader> [--library-path <dir>] @BIN [args...]
 *
 * Then, as the kernel would for an executable with glibc's loader as its
 * interpreter: Soloist's segments are mapped from the memfd, the loader's from
 * its file, a stack is laid out with the program's arguments, environment and
 * auxiliary vector, and the loader is entered. It finds the program already in
 * memory and carries on from there as after any exec.
 *
 * Not the loader run on "/proc/self/fd/N", as this did first: that has the
 * loader open the memfd again by its path, and Android 17 refuses an app any
 * open of a memory file (avc: denied { open } ... appdomain_tmpfs). Mapping
 * the descriptor already held needs no open. Where the mapping is refused the
 * old way is tried, which older Android allows.
 *
 * glibc's LD_PRELOAD and LD_AUDIT come as SQ_LD_PRELOAD and SQ_LD_AUDIT: under
 * their own names Android's linker, which starts this launcher, would try to
 * load them. They get their names back just before the loader runs. */
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <signal.h>
#include <unistd.h>

extern char **environ;

static uintptr_t page;

#define DOWN(x) ((uintptr_t)(x) & ~(page - 1))
#define UP(x) DOWN((uintptr_t)(x) + page - 1)

/* One ELF file, mapped. */
struct image {
    uintptr_t bias;   /* what its addresses are moved by */
    uintptr_t entry;  /* where it starts, moved */
    uintptr_t phdr;   /* its program headers, in memory */
    size_t phnum;
};

static int prot_of(uint32_t flags) {
    return ((flags & PF_R) ? PROT_READ : 0) | ((flags & PF_W) ? PROT_WRITE : 0) | ((flags & PF_X) ? PROT_EXEC : 0);
}

/* Maps the ELF open at [fd] as the kernel does an executable's or an
 * interpreter's. Returns 0, or -1 with errno set and a line said. */
static int map_elf(int fd, const char *what, struct image *out) {
    Elf64_Ehdr eh;
    if (pread(fd, &eh, sizeof eh, 0) != (ssize_t)sizeof eh || memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 ||
        eh.e_ident[EI_CLASS] != ELFCLASS64 || eh.e_phentsize != sizeof(Elf64_Phdr)) {
        fprintf(stderr, "SQLAUNCH %s: not a 64-bit ELF\n", what);
        errno = ENOEXEC;
        return -1;
    }
    size_t size = (size_t)eh.e_phnum * sizeof(Elf64_Phdr);
    Elf64_Phdr *ph = malloc(size);
    if (!ph || pread(fd, ph, size, (off_t)eh.e_phoff) != (ssize_t)size) {
        fprintf(stderr, "SQLAUNCH %s: no program headers\n", what);
        errno = ENOEXEC;
        return -1;
    }

    uintptr_t lo = UINTPTR_MAX, hi = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type != PT_LOAD) continue;
        if (DOWN(ph[i].p_vaddr) < lo) lo = DOWN(ph[i].p_vaddr);
        if (UP(ph[i].p_vaddr + ph[i].p_memsz) > hi) hi = UP(ph[i].p_vaddr + ph[i].p_memsz);
    }
    if (lo >= hi) {
        errno = ENOEXEC;
        return -1;
    }

    /* The whole span reserved first, so the segments land together. */
    void *span = mmap(eh.e_type == ET_EXEC ? (void *)lo : NULL, hi - lo, PROT_NONE,
                      MAP_PRIVATE | MAP_ANONYMOUS | (eh.e_type == ET_EXEC ? MAP_FIXED : 0), -1, 0);
    if (span == MAP_FAILED) {
        fprintf(stderr, "SQLAUNCH %s: no room: %s\n", what, strerror(errno));
        return -1;
    }
    uintptr_t bias = eh.e_type == ET_EXEC ? 0 : (uintptr_t)span - lo;

    uintptr_t phdr = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        const Elf64_Phdr *p = &ph[i];
        if (p->p_type == PT_PHDR) phdr = bias + p->p_vaddr;
        if (p->p_type != PT_LOAD) continue;
        if (!phdr && p->p_offset == 0) phdr = bias + p->p_vaddr + eh.e_phoff;

        uintptr_t start = bias + p->p_vaddr;
        uintptr_t file_end = start + p->p_filesz;
        uintptr_t mem_end = start + p->p_memsz;
        int prot = prot_of(p->p_flags);
        int zeroing = p->p_memsz > p->p_filesz && (file_end & (page - 1));

        if (p->p_filesz) {
            void *at = mmap((void *)DOWN(start), UP(file_end) - DOWN(start), prot | (zeroing ? PROT_WRITE : 0),
                            MAP_PRIVATE | MAP_FIXED, fd, (off_t)DOWN(p->p_offset));
            if (at == MAP_FAILED) {
                fprintf(stderr, "SQLAUNCH %s: segment %d not mapped: %s\n", what, i, strerror(errno));
                return -1;
            }
        }
        /* The rest of the last page of file data, and the zeroed memory after it. */
        if (zeroing) {
            memset((void *)file_end, 0, UP(file_end) - file_end);
            if (!(prot & PROT_WRITE)) mprotect((void *)DOWN(file_end), page, prot);
        }
        if (UP(mem_end) > UP(file_end)) {
            void *at = mmap((void *)UP(file_end), UP(mem_end) - UP(file_end), prot,
                            MAP_PRIVATE | MAP_FIXED | MAP_ANONYMOUS, -1, 0);
            if (at == MAP_FAILED) {
                fprintf(stderr, "SQLAUNCH %s: bss not mapped: %s\n", what, strerror(errno));
                return -1;
            }
        }
    }
    out->bias = bias;
    out->entry = bias + eh.e_entry;
    out->phdr = phdr;
    out->phnum = eh.e_phnum;
    free(ph);
    return 0;
}

/* Copies [s] to the top of the stack being built, returning where it went. */
static char *push_string(char **top, const char *s) {
    size_t n = strlen(s) + 1;
    *top -= n;
    memcpy(*top, s, n);
    return *top;
}

/* Enters [loader] for [program] as the kernel would. Returns only on failure. */
static void run_mapped(int mem, const char *loader, const char *name, char **args, int nargs) {
    struct image prog, interp;
    if (map_elf(mem, "soloist", &prog) != 0) return;
    int lfd = open(loader, O_RDONLY | O_CLOEXEC);
    if (lfd < 0) {
        fprintf(stderr, "SQLAUNCH cannot open %s: %s\n", loader, strerror(errno));
        return;
    }
    if (map_elf(lfd, "loader", &interp) != 0) return;
    close(lfd);

    size_t stack_size = 8u << 20;
    char *stack = mmap(NULL, stack_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_STACK, -1, 0);
    if (stack == MAP_FAILED) return;
    char *top = stack + stack_size;

    int envc = 0;
    while (environ[envc]) envc++;
    char **argv_at = malloc(sizeof(char *) * (size_t)(nargs + 1));
    char **envp_at = malloc(sizeof(char *) * (size_t)(envc + 1));
    argv_at[0] = push_string(&top, name);
    for (int i = 1; i < nargs; i++) argv_at[i] = push_string(&top, args[i]);
    for (int i = 0; i < envc; i++) envp_at[i] = push_string(&top, environ[i]);
    char *platform = push_string(&top, "aarch64");
    char *execfn = argv_at[0];
    top -= 16;
    unsigned char *random = (unsigned char *)top;
    if (syscall(__NR_getrandom, random, 16, 0) != 16) memset(random, 0x5a, 16);

    uint64_t aux[] = {
        AT_PHDR, prog.phdr,
        AT_PHENT, sizeof(Elf64_Phdr),
        AT_PHNUM, prog.phnum,
        AT_PAGESZ, page,
        AT_BASE, interp.bias,
        AT_FLAGS, 0,
        AT_ENTRY, prog.entry,
        AT_UID, getuid(),
        AT_EUID, geteuid(),
        AT_GID, getgid(),
        AT_EGID, getegid(),
        AT_SECURE, 0,
        AT_RANDOM, (uint64_t)(uintptr_t)random,
        AT_HWCAP, getauxval(AT_HWCAP),
        AT_HWCAP2, getauxval(AT_HWCAP2),
        AT_CLKTCK, (uint64_t)sysconf(_SC_CLK_TCK),
        AT_PLATFORM, (uint64_t)(uintptr_t)platform,
        AT_EXECFN, (uint64_t)(uintptr_t)execfn,
        AT_SYSINFO_EHDR, getauxval(AT_SYSINFO_EHDR),
#ifdef AT_MINSIGSTKSZ
        AT_MINSIGSTKSZ, getauxval(AT_MINSIGSTKSZ),
#endif
        AT_NULL, 0,
    };

    size_t words = 1 + (size_t)nargs + 1 + (size_t)envc + 1 + sizeof aux / sizeof *aux;
    uint64_t *sp = (uint64_t *)(((uintptr_t)top - words * 8) & ~(uintptr_t)15);
    uint64_t *w = sp;
    *w++ = (uint64_t)nargs;
    for (int i = 0; i < nargs; i++) *w++ = (uint64_t)(uintptr_t)argv_at[i];
    *w++ = 0;
    for (int i = 0; i < envc; i++) *w++ = (uint64_t)(uintptr_t)envp_at[i];
    *w++ = 0;
    memcpy(w, aux, sizeof aux);

    /* As after an exec: every signal back to its default and none blocked.
     * Android's own handlers (the crash reporter's) are bionic code that the
     * loader's world knows nothing of. */
    for (int sig = 1; sig < 65; sig++) {
        if (sig == SIGKILL || sig == SIGSTOP) continue;
        struct sigaction dfl;
        memset(&dfl, 0, sizeof dfl);
        dfl.sa_handler = SIG_DFL;
        sigaction(sig, &dfl, NULL);
    }
    sigset_t none;
    sigemptyset(&none);
    sigprocmask(SIG_SETMASK, &none, NULL);

    /* As at the start of any program: the stack, nothing to run at exit. */
    __asm__ volatile(
        "mov sp, %0\n"
        "mov x0, xzr\n"
        "mov x29, xzr\n"
        "mov x30, xzr\n"
        "br %1\n"
        :
        : "r"(sp), "r"(interp.entry)
        : "memory");
    __builtin_unreachable();
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <binary> <loader> [--library-path <dir>] @BIN [args...]\n", argv[0]);
        return 2;
    }
    page = (uintptr_t)getauxval(AT_PAGESZ);
    if (!page) page = 4096;

    int src = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        fprintf(stderr, "SQLAUNCH cannot open %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    struct stat st;
    if (fstat(src, &st) != 0) return 1;
    /* Not close-on-exec: the old way's loader opens it by its /proc path. */
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

    /* The program's own arguments: what follows @BIN; the library path, as
     * the loader would take it run by hand, goes in the environment. */
    int bin = -1;
    for (int i = 3; i < argc; i++) {
        if (strcmp(argv[i], "--library-path") == 0 && i + 1 < argc) setenv("LD_LIBRARY_PATH", argv[i + 1], 1);
        if (strcmp(argv[i], "@BIN") == 0) {
            bin = i;
            break;
        }
    }
    if (bin > 0) {
        run_mapped(mem, argv[2], argv[1], argv + bin, argc - bin);
        fprintf(stderr, "SQLAUNCH mapping refused, trying the loader on the memfd\n");
    }

    char path[64];
    snprintf(path, sizeof path, "/proc/self/fd/%d", mem);
    for (int i = 3; i < argc; i++) {
        if (strcmp(argv[i], "@BIN") == 0) argv[i] = path;
    }
    execv(argv[2], argv + 2);
    fprintf(stderr, "SQLAUNCH exec %s: %s\n", argv[2], strerror(errno));
    return 1;
}
