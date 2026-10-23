#ifndef WOWFOREVER_ARM64_PROBE_COMMON_H
#define WOWFOREVER_ARM64_PROBE_COMMON_H

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <ctype.h>
#include <io.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    const char *log_path;
    const char *run_id;
} ProbeArgs;

typedef struct {
    FILE *file;
    const char *run_id;
    int log_failed;
} ProbeLog;

static int probe_is_absolute_windows_path(const char *path)
{
    size_t i;

    if (path == NULL || path[0] == '\0' || strlen(path) > 32767)
        return 0;
    for (i = 0; path[i] != '\0'; ++i) {
        if ((unsigned char)path[i] < 0x20 || path[i] == '\r' || path[i] == '\n')
            return 0;
    }
    if (isalpha((unsigned char)path[0]) && path[1] == ':' &&
        (path[2] == '\\' || path[2] == '/'))
        return 1;
    return (path[0] == '\\' && path[1] == '\\');
}

static int probe_is_strict_run_id(const char *run_id)
{
    size_t i;

    if (run_id == NULL || run_id[0] == '\0' || strlen(run_id) > 128)
        return 0;
    for (i = 0; run_id[i] != '\0'; ++i) {
        if (!(isalnum((unsigned char)run_id[i]) || run_id[i] == '_' ||
              run_id[i] == '-' || run_id[i] == '.'))
            return 0;
    }
    return 1;
}

static int probe_parse_args(int argc, char **argv, ProbeArgs *args)
{
    int i;
    int saw_log = 0;
    int saw_run_id = 0;

    if (args == NULL)
        return 2;
    args->log_path = NULL;
    args->run_id = NULL;
    for (i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--log") == 0) {
            if (saw_log || i + 1 >= argc || argv[i + 1][0] == '\0')
                return 2;
            args->log_path = argv[++i];
            saw_log = 1;
        } else if (strcmp(argv[i], "--run-id") == 0) {
            if (saw_run_id || i + 1 >= argc ||
                !probe_is_strict_run_id(argv[i + 1]))
                return 2;
            args->run_id = argv[++i];
            saw_run_id = 1;
        } else {
            return 2;
        }
    }
    if (!saw_log || !probe_is_absolute_windows_path(args->log_path))
        return 2;
    return 0;
}

static int probe_open_log(ProbeLog *log, const ProbeArgs *args)
{
    log->file = fopen(args->log_path, "wb");
    log->run_id = args->run_id;
    log->log_failed = log->file == NULL;
    return log->file != NULL;
}

static int probe_flush_log(ProbeLog *log)
{
    if (log == NULL || log->file == NULL || fflush(log->file) != 0 ||
        _commit(_fileno(log->file)) != 0) {
        if (log != NULL)
            log->log_failed = 1;
        return 0;
    }
    return 1;
}

static int probe_log_line(ProbeLog *log, const char *format, ...)
{
    va_list ap;
    int ok = 1;

    if (log == NULL || log->file == NULL)
        return 0;
    if (log->run_id != NULL &&
        fprintf(log->file, "RUN-ID=%s ", log->run_id) < 0)
        ok = 0;
    va_start(ap, format);
    if (vfprintf(log->file, format, ap) < 0)
        ok = 0;
    va_end(ap);
    if (fputc('\n', log->file) == EOF)
        ok = 0;
    if (!probe_flush_log(log))
        ok = 0;
    if (!ok)
        log->log_failed = 1;
    return ok;
}

static void probe_stage_begin(ProbeLog *log, const char *name)
{
    probe_log_line(log, "STAGE-BEGIN name=%s", name);
}

static int probe_stage_end(ProbeLog *log, const char *name, int returncode)
{
    probe_log_line(log, "STAGE-END name=%s returncode=%d", name, returncode);
    return returncode == 0;
}

static int probe_finish(ProbeLog *log, const char *name, int returncode)
{
    int result = returncode;

    probe_log_line(log, "PROBE-END name=%s returncode=%d", name, returncode);
    if (log->log_failed || !probe_flush_log(log))
        result = 1;
    if (fclose(log->file) != 0)
        result = 1;
    log->file = NULL;
    return result;
}

#endif
