#include "probe_common.h"

int main(int argc, char **argv)
{
    ProbeArgs args;
    ProbeLog log;
    SYSTEM_INFO system_info;
    int parse_result;
    int result = 0;

    parse_result = probe_parse_args(argc, argv, &args);
    if (parse_result != 0)
        return parse_result;
    if (!probe_open_log(&log, &args))
        return 2;

    probe_log_line(&log, "PROBE-BEGIN name=HelloArm64 pe_machine=0xAA64");
    probe_stage_begin(&log, "architecture");
    GetNativeSystemInfo(&system_info);
    probe_log_line(&log, "ARCH processor_architecture=%u pointer_bits=%u",
                   (unsigned)system_info.wProcessorArchitecture,
                   (unsigned)(sizeof(void *) * 8));
    if (system_info.wProcessorArchitecture != PROCESSOR_ARCHITECTURE_ARM64 ||
        sizeof(void *) != 8) {
        result = 1;
    }
    probe_stage_end(&log, "architecture", result);

    if (result == 0)
        result = 42;
    return probe_finish(&log, "HelloArm64", result);
}
