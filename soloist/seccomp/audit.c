/* LD_AUDIT module for Soloist on Android: catches the SIGSYS the app sandbox's
 * seccomp filter raises for a system call it does not allow, says which one,
 * and makes the call return -ENOSYS so glibc takes its fallback. The call is
 * never executed: the kernel has already refused it. Raw syscalls only, since
 * it runs before libc is set up. */
typedef unsigned long u64;
static long sys(long n, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}
#define SYS_write 64
#define SYS_rt_sigaction 134
#define SYS_rt_sigreturn 139
static unsigned char seen[512];
static void put(const char *s, int n) { sys(SYS_write, 2, (long)s, n, 0); }
static void handler(int sig, void *info, void *uc) {
    (void)sig;
    int nr = *(int *)((char *)info + 24);
    if (nr >= 0 && nr < 512 && !seen[nr]) {
        seen[nr] = 1;
        char b[48] = "SQSECCOMP syscall=";
        int i = 18, v = nr; char d[12]; int k = 0;
        do { d[k++] = '0' + v % 10; v /= 10; } while (v);
        while (k) b[i++] = d[--k];
        b[i++] = '\n';
        put(b, i);
    }
    *(long *)((char *)uc + 184) = -38; /* x0 = -ENOSYS */
}
__attribute__((naked)) static void restorer(void) {
    __asm__ volatile("mov x8, #139\n svc 0");
}
struct ksa { void *h; u64 flags; void *restorer; u64 mask; };
__attribute__((visibility("default"))) unsigned int la_version(unsigned int v) {
    struct ksa sa = { (void *)handler, 0x4 | 0x04000000 /* SA_SIGINFO|SA_RESTORER */, (void *)restorer, 0 };
    sys(SYS_rt_sigaction, 31 /* SIGSYS */, (long)&sa, 0, 8);
    put("SQSECCOMP armed\n", 16);
    return v;
}
