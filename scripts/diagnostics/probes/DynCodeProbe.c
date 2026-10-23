/*
 * DynCodeProbe.exe — control mínimo de código generado en memoria (x86-64).
 *
 * Pregunta que responde: ¿el runtime es capaz de EJECUTAR código máquina que
 * escribimos nosotros en una región reservada (RW -> RX)?  Los loaders de juegos
 * modernos generan/hookean código en memoria, así que un fallo aquí acota el
 * problema de WowB.exe (violación de acceso al ejecutar) a la capa de runtime.
 *
 * NO toca el juego ni modifica otros procesos: todo ocurre en su propio proceso.
 *
 * Salida: C:\wow\diag\dyncode-probe.txt (se vuelca y se fuerza a disco antes de
 * cada llamada, para que un fallo de ejecución deje rastro de dónde murió).
 *
 * Compilar (host Linux, MinGW-w64):
 *   x86_64-w64-mingw32-g++ -O2 -static -static-libgcc -static-libstdc++ \
 *     -mwindows -o DynCodeProbe.exe DynCodeProbe.c
 */

#include <windows.h>
#include <io.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>

static FILE *g_log = NULL;
static int g_failures = 0;
static int g_log_failed = 0;

/* Test-only fixture switches; production builds leave both at zero. */
#ifndef DYNCODE_FORCE_API_MISSING
#define DYNCODE_FORCE_API_MISSING 0
#endif
#ifndef DYNCODE_FORCE_FLUSH_FAILURE
#define DYNCODE_FORCE_FLUSH_FAILURE 0
#endif

static int log_flush(void) {
    if (!g_log) return 0;
    if (fflush(g_log) != 0) return 0;
    return _commit(_fileno(g_log)) == 0;
}

static void log_line(const char *fmt, ...) {
    if (!g_log) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_log, fmt, ap);
    va_end(ap);
    fputc('\n', g_log);
    /* Durable before a generated-code call, not merely visible in stdio. */
    if (!log_flush()) g_log_failed = 1;
}

static int flush_code(const char *tag, void *address, size_t len) {
    BOOL result;

#if DYNCODE_FORCE_FLUSH_FAILURE
    (void)address;
    (void)len;
    SetLastError(ERROR_ACCESS_DENIED);
    result = FALSE;
#else
    result = FlushInstructionCache(GetCurrentProcess(), address, len);
#endif
    if (!result) {
        log_line("%s FLUSH-FAIL gle=%lu", tag,
                 (unsigned long)GetLastError());
        return 0;
    }
    return 1;
}

/* Reserva una región RW, escribe `code` y la pasa a RX. */
static void *emit_code(const unsigned char *code, size_t len, const char *tag) {
    void *mem = VirtualAlloc(NULL, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!mem) {
        log_line("%s ALLOC-FAIL gle=%lu", tag, (unsigned long)GetLastError());
        return NULL;
    }
    memcpy(mem, code, len);
    DWORD old = 0;
    if (!VirtualProtect(mem, 4096, PAGE_EXECUTE_READ, &old)) {
        log_line("%s PROTECT-FAIL gle=%lu", tag, (unsigned long)GetLastError());
        VirtualFree(mem, 0, MEM_RELEASE);
        return NULL;
    }
    if (!flush_code(tag, mem, len)) {
        VirtualFree(mem, 0, MEM_RELEASE);
        return NULL;
    }
    log_line("%s READY addr=%p len=%zu", tag, mem, len);
    return mem;
}

typedef int (*fn_void)(void);
typedef int (*fn_i64_pair)(int64_t, int64_t);

static void case_failed(const char *tag, const char *reason) {
    ++g_failures;
    log_line("%s FAIL %s", tag, reason);
}

static int alias_is_expected(void *address, DWORD expected, const char *label) {
    MEMORY_BASIC_INFORMATION info;
    SIZE_T queried = VirtualQuery(address, &info, sizeof(info));
    DWORD actual = queried ? (info.Protect & 0xff) : PAGE_NOACCESS;

    log_line("case6 VIEW %s address=%p protect=0x%lx expected=0x%lx",
             label, address, (unsigned long)actual, (unsigned long)expected);
    return queried != 0 && actual == expected;
}

static int run_alias_case(void) {
    const SIZE_T page_size = 4096;
    const size_t code_len = 6;
    const unsigned char first_code[] =
        { 0xB8, 0x11, 0x00, 0x00, 0x00, 0xC3 }; /* mov eax,17; ret */
    const unsigned char second_code[] =
        { 0xB8, 0x1D, 0x00, 0x00, 0x00, 0xC3 }; /* mov eax,29; ret */
    HANDLE section = NULL;
    void *rw = NULL;
    void *rx = NULL;
    int result = 1;

    log_line("case6 BEGIN shared-section");
    section = CreateFileMappingW(INVALID_HANDLE_VALUE, NULL,
                                 PAGE_EXECUTE_READWRITE, 0,
                                 (DWORD)page_size, NULL);
    if (!section) {
        log_line("case6 SECTION-FAIL gle=%lu", (unsigned long)GetLastError());
        case_failed("case6", "section");
        return 0;
    }
    rw = MapViewOfFile(section, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0,
                       page_size);
    rx = MapViewOfFile(section, FILE_MAP_READ | FILE_MAP_EXECUTE, 0, 0,
                       page_size);
    if (!rw || !rx) {
        log_line("case6 MAP-FAIL rw=%p rx=%p gle=%lu", rw, rx,
                 (unsigned long)GetLastError());
        case_failed("case6", "map");
        goto cleanup;
    }
    if (!alias_is_expected(rw, PAGE_READWRITE, "rw") ||
        !alias_is_expected(rx, PAGE_EXECUTE_READ, "rx")) {
        case_failed("case6", "unexpected-view-protection");
        goto cleanup;
    }

    memcpy(rw, first_code, code_len);
    if (!flush_code("case6-first", rx, code_len)) {
        case_failed("case6", "first-flush");
        goto cleanup;
    }
    log_line("case6 FIRST CALLING rw=%p rx=%p value=17", rw, rx);
    {
        int first = ((fn_void)rx)();
        log_line("case6 FIRST RESULT=%d (esperado 17)", first);
        if (first != 17) {
            case_failed("case6", "first-value");
            goto cleanup;
        }
    }

    memcpy(rw, second_code, code_len);
    if (!flush_code("case6-second", rx, code_len)) {
        case_failed("case6", "second-flush");
        goto cleanup;
    }
    log_line("case6 SECOND CALLING rw=%p rx=%p value=29", rw, rx);
    {
        int second = ((fn_void)rx)();
        log_line("case6 SECOND RESULT=%d (esperado 29)", second);
        if (second != 29) {
            case_failed("case6", "second-value");
            goto cleanup;
        }
    }
    log_line("case6 PASS shared-section rw-write rx-execute updated=1");
    result = 0;

cleanup:
    if (rw) UnmapViewOfFile(rw);
    if (rx) UnmapViewOfFile(rx);
    if (section) CloseHandle(section);
    return result == 0;
}

static const char *command_line_log_path(int argc, char **argv) {
    int i;
    for (i = 1; i + 1 < argc; ++i) {
        if (strcmp(argv[i], "--log") == 0) return argv[i + 1];
    }
    return NULL;
}

int main(int argc, char **argv) {
    const char *log_path = command_line_log_path(argc, argv);
    CreateDirectoryA("C:\\wow\\diag", NULL);
    g_log = fopen(log_path != NULL ? log_path : "C:\\wow\\diag\\dyncode-probe.txt", "w");
    if (!g_log) return 2;

    SYSTEM_INFO si;
    GetNativeSystemInfo(&si);
    log_line("DYNCODE-BEGIN arch=%u page=%lu",
             (unsigned)si.wProcessorArchitecture,
             (unsigned long)si.dwPageSize);

    /* Caso 1: mov eax, 42 ; ret  -> debe devolver 42 */
    {
        unsigned char code[] = { 0xB8, 0x2A, 0x00, 0x00, 0x00, 0xC3 };
        void *mem = emit_code(code, sizeof(code), "case1-const");
        if (mem) {
            fn_void f = (fn_void)mem;
            log_line("case1 CALLING");
            int r = f();
            log_line("case1 RESULT=%d (esperado 42)", r);
            if (r != 42) case_failed("case1", "unexpected-value");
            VirtualFree(mem, 0, MEM_RELEASE);
        } else {
            case_failed("case1", "emit");
        }
    }

    /* Caso 2: lea eax,[rcx+rdx] ; ret -> con (100,23) debe devolver 123.
     * Ambos argumentos deben llegar por RCX/RDX en la ABI Windows x64. */
    {
        unsigned char code[] = { 0x8D, 0x04, 0x11, 0xC3 };
        void *mem = emit_code(code, sizeof(code), "case2-args");
        if (mem) {
            fn_i64_pair f = (fn_i64_pair)mem;
            log_line("case2 CALLING");
            int r = f((int64_t)100, (int64_t)23);
            log_line("case2 RESULT=%d (esperado 123)", r);
            if (r != 123) case_failed("case2", "unexpected-value");
            VirtualFree(mem, 0, MEM_RELEASE);
        } else {
            case_failed("case2", "emit");
        }
    }

    /* Caso 3: bucle con salto: suma 1..n
     *   xor eax,eax ; test ecx,ecx ; jle done ; loop: add eax,ecx ; dec ecx ;
     *   jnz loop ; done: ret
     *   -> con n=10 debe devolver 55
     */
    {
        unsigned char code[] = { 0x31, 0xC0, 0x85, 0xC9, 0x7E, 0x06,
                                 0x01, 0xC8, 0xFF, 0xC9, 0x75, 0xFA, 0xC3 };
        void *mem = emit_code(code, sizeof(code), "case3-branch");
        if (mem) {
            log_line("case3 CALLING");
            /* n va en RCX (primer argumento en x64 Windows) */
            int r = ((int (*)(int64_t))mem)((int64_t)10);
            log_line("case3 RESULT=%d (esperado 55)", r);
            if (r != 55) case_failed("case3", "unexpected-value");
            VirtualFree(mem, 0, MEM_RELEASE);
        } else {
            case_failed("case3", "emit");
        }
    }

    /* Caso 4: el código generado llama a una API real de Windows
     *   mov rax, imm64(GetTickCount64) ; call rax ; ret
     * Es el patrón que usan los loaders protegidos (thunks generados).
     */
    {
        HMODULE k32 = GetModuleHandleA("kernel32.dll");
        void *api;
#if DYNCODE_FORCE_API_MISSING
        (void)k32;
        api = NULL;
        SetLastError(ERROR_PROC_NOT_FOUND);
#else
        api = k32 ? (void *)GetProcAddress(k32, "GetTickCount64") : NULL;
#endif
        if (!api) {
            log_line("case4 FAIL API-UNAVAILABLE gle=%lu",
                     (unsigned long)GetLastError());
            case_failed("case4", "api-unavailable");
        } else {
            unsigned char code[32];
            size_t n = 0;
            code[n++] = 0x48; code[n++] = 0xB8;          /* mov rax, imm64 */
            memcpy(code + n, &api, sizeof(api)); n += sizeof(api);
            code[n++] = 0x48; code[n++] = 0x83; code[n++] = 0xEC;
            code[n++] = 0x28;                            /* shadow+alignment */
            code[n++] = 0xFF; code[n++] = 0xD0;          /* call rax */
            code[n++] = 0x48; code[n++] = 0x83; code[n++] = 0xC4;
            code[n++] = 0x28;                            /* release stack area */
            code[n++] = 0xC3;                            /* ret */
            void *mem = emit_code(code, n, "case4-callapi");
            if (mem) {
                uint64_t before = GetTickCount64();
                log_line("case4 api=%p CALLING", api);
                uint64_t r = ((uint64_t (*)(void))mem)();
                uint64_t after = GetTickCount64();
                log_line("case4 RESULT=%llu", (unsigned long long)r);
                log_line("case4 RANGE before=%llu after=%llu",
                         (unsigned long long)before, (unsigned long long)after);
                if (r < before || r > after)
                    case_failed("case4", "result-out-of-range");
                VirtualFree(mem, 0, MEM_RELEASE);
            } else {
                case_failed("case4", "emit");
            }
        }
    }

    /* Caso 5: liberar la primera región y ejecutar código en otra nueva.
     * Muchos empaquetadores reasignan y re-protegen; aquí comprobamos que la
     * ejecución sigue funcionando tras liberar/mover memoria.
     */
    {
        unsigned char code[] = { 0xB8, 0x63, 0x00, 0x00, 0x00, 0xC3 }; /* mov eax,99 ; ret */
        void *first = emit_code(code, sizeof(code), "case5-first");
        if (first) {
            log_line("case5 first CALLING");
            int r1 = ((fn_void)first)();
            log_line("case5 first RESULT=%d (esperado 99)", r1);
            if (r1 != 99) case_failed("case5", "first-value");
            VirtualFree(first, 0, MEM_RELEASE);
            log_line("case5 FREED");
            void *second = emit_code(code, sizeof(code), "case5-second");
            if (second) {
                log_line("case5 second CALLING");
                int r2 = ((fn_void)second)();
                log_line("case5 second RESULT=%d (esperado 99)", r2);
                if (r2 != 99) case_failed("case5", "second-value");
                VirtualFree(second, 0, MEM_RELEASE);
            } else {
                case_failed("case5", "second-emit");
            }
        } else {
            case_failed("case5", "first-emit");
        }
    }

    if (!run_alias_case()) {
        /* run_alias_case records the specific reason before returning. */
    }
    if (g_log_failed) ++g_failures;
    log_line("DYNCODE-END result=%d failures=%d", g_failures == 0 ? 0 : 1,
             g_failures);
    {
        int result = g_failures == 0 && !g_log_failed ? 0 : 1;
        if (!log_flush()) result = 1;
        if (fclose(g_log) != 0) result = 1;
        return result;
    }
}
