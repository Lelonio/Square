/* LD_PRELOAD for Soloist on Android: keeps libsqaudit's SIGSYS handler in
 * place. Everything else about signals is Soloist's own. */
typedef void (*fn_sigaction)(void);
extern void *dlsym(void *, const char *);
extern int dprintf(int, const char *, ...);
#define RTLD_NEXT ((void *)-1l)
#define SIGSYS 31
#define EXPORT __attribute__((visibility("default")))
static int (*real_sigaction)(int, const void *, void *);
EXPORT int sigaction(int sig, const void *act, void *old) {
    if (!real_sigaction) real_sigaction = (int (*)(int, const void *, void *))dlsym(RTLD_NEXT, "sigaction");
    if (sig == SIGSYS && act) {
        dprintf(2, "SQSECCOMP kept the SIGSYS handler\n");
        return real_sigaction(sig, (const void *)0, old);
    }
    return real_sigaction(sig, act, old);
}
EXPORT void *signal(int sig, void *handler) {
    if (sig == SIGSYS) return (void *)0;
    static void *(*real_signal)(int, void *);
    if (!real_signal) real_signal = (void *(*)(int, void *))dlsym(RTLD_NEXT, "signal");
    return real_signal(sig, handler);
}

/* A thread with SIGSYS blocked is killed by the kernel when the filter
 * refuses one of its calls, handler or not; so SIGSYS is never blocked. */
typedef struct { unsigned long w[16]; } sigset128;
static const sigset128 *unblock_sys(const sigset128 *set, sigset128 *copy) {
    if (!set) return set;
    *copy = *set;
    copy->w[0] &= ~(1ul << (SIGSYS - 1));
    return copy;
}
EXPORT int sigprocmask(int how, const void *set, void *old) {
    static int (*real)(int, const void *, void *);
    if (!real) real = (int (*)(int, const void *, void *))dlsym(RTLD_NEXT, "sigprocmask");
    sigset128 c;
    return real(how, how == 2 /* SIG_SETMASK */ || how == 0 /* SIG_BLOCK */ ? unblock_sys(set, &c) : set, old);
}
EXPORT int pthread_sigmask(int how, const void *set, void *old) {
    static int (*real)(int, const void *, void *);
    if (!real) real = (int (*)(int, const void *, void *))dlsym(RTLD_NEXT, "pthread_sigmask");
    sigset128 c;
    return real(how, how == 2 || how == 0 ? unblock_sys(set, &c) : set, old);
}
