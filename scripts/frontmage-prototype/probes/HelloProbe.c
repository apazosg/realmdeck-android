#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>

static const char *log_path(int argc, char **argv) {
    int i;
    for (i = 1; i + 1 < argc; ++i) {
        if (strcmp(argv[i], "--log") == 0) return argv[i + 1];
    }
    return "C:\\wow\\diag\\hello-probe.txt";
}

int main(int argc, char **argv) {
    SYSTEM_INFO info;
    FILE *log = fopen(log_path(argc, argv), "w");
    if (log == NULL) return 2;
    GetNativeSystemInfo(&info);
    fprintf(log, "HELLO-BEGIN arch=%u\n", (unsigned)info.wProcessorArchitecture);
    fprintf(log, "HELLO-EXIT=42\n");
    fflush(log);
    fclose(log);
    return 42;
}
