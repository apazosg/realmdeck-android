package com.wowforever.runtimeprobe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Bounded, read-only ELF evidence for the private runtime probe. */
public final class Box64Diagnostics {
    private static final int ELF_HEADER_SIZE = 64;
    private static final int PROGRAM_HEADER_SIZE = 56;
    private static final int MAX_PROGRAM_HEADERS = 128;
    private static final int MAX_INTERPRETER_BYTES = 4096;
    private static final int PT_INTERP = 3;
    private static final int EM_AARCH64 = 183;
    private static final int ET_DYN = 3;
    private static final String AARCH64_LOADER = "ld-linux-aarch64.so.1";

    private Box64Diagnostics() {}

    public static boolean isExecutableFile(File file) {
        return file != null && file.isFile() && !isSymlink(file) && file.length() > 0 && file.canExecute();
    }

    public static boolean isLaunchable(File rootDir, File file) {
        if (!isExecutableFile(file)) return false;
        ElfInfo elf = inspect(file);
        File loader = resolveInterpreter(rootDir, file);
        if (!elf.valid || elf.machine != EM_AARCH64 || loader == null) return false;
        ElfInfo loaderElf = inspect(loader);
        return loaderElf.valid && loaderElf.type == ET_DYN && loaderElf.machine == EM_AARCH64 &&
            loaderElf.interpreter == null;
    }

    /** Resolves the bundled loader by ELF identity, never by the producer's absolute path. */
    public static File resolveInterpreter(File rootDir, File file) {
        if (rootDir == null || file == null) return null;
        ElfInfo elf = inspect(file);
        if (!elf.valid || elf.machine != EM_AARCH64 || elf.interpreter == null) return null;
        if (!AARCH64_LOADER.equals(new File(elf.interpreter).getName())) return null;
        File loader = new File(new File(rootDir, "lib"), AARCH64_LOADER);
        return isExecutableFile(loader) ? loader : null;
    }

    public static String describe(String phase, File file) {
        StringBuilder result = new StringBuilder(phase).append(" path=").append(safe(file == null ? null : file.getAbsolutePath()));
        result.append(" exists=").append(file != null && file.exists());
        result.append(" isFile=").append(file != null && file.isFile());
        result.append(" canExecute=").append(file != null && file.canExecute());
        result.append(" size=").append(file == null ? -1 : file.length());
        result.append(" symlink=").append(file != null && isSymlink(file));
        result.append(" symlinkTarget=").append(symlinkTarget(file));
        if (file != null && file.isFile()) result.append(" sha256=").append(sha256(file));
        ElfInfo elf = file == null ? ElfInfo.error("no-file") : inspect(file);
        result.append(" elf=").append(elf.valid);
        result.append(" elfType=").append(elf.type);
        result.append(" machine=").append(machineName(elf.machine));
        result.append(" ptInterp=").append(safe(elf.interpreter));
        result.append(" interpreterExists=").append(elf.interpreterFile != null && elf.interpreterFile.exists());
        result.append(" interpreterIsFile=").append(elf.interpreterFile != null && elf.interpreterFile.isFile());
        File rootDir = file == null ? null : findRootDir(file);
        File resolvedLoader = resolveInterpreter(rootDir, file);
        result.append(" resolvedLoader=").append(safe(resolvedLoader == null ? null : resolvedLoader.getAbsolutePath()));
        result.append(" resolvedLoaderExists=").append(resolvedLoader != null && resolvedLoader.isFile());
        if (resolvedLoader != null) {
            ElfInfo loaderElf = inspect(resolvedLoader);
            result.append(" loaderElf=").append(loaderElf.valid);
            result.append(" loaderType=").append(loaderElf.type);
            result.append(" loaderMachine=").append(machineName(loaderElf.machine));
            result.append(" loaderPtInterp=").append(safe(loaderElf.interpreter));
        }
        if (elf.error != null) result.append(" elfError=").append(safe(elf.error));
        return result.toString();
    }

    private static ElfInfo inspect(File file) {
        if (file == null || !file.isFile()) return ElfInfo.error("not-file");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length < ELF_HEADER_SIZE) return ElfInfo.error("short-header");
            byte[] header = new byte[ELF_HEADER_SIZE];
            input.readFully(header);
            if ((header[0] & 0xff) != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                return ElfInfo.error("not-elf");
            }
            if ((header[4] & 0xff) != 2 || (header[5] & 0xff) != 1) return ElfInfo.error("not-elf64-le");
            int type = u16(header, 16);
            int machine = u16(header, 18);
            long programOffset = u64(header, 32);
            int entrySize = u16(header, 54);
            int count = u16(header, 56);
            if (entrySize < PROGRAM_HEADER_SIZE || count > MAX_PROGRAM_HEADERS || programOffset < 0 ||
                programOffset > length || (long)entrySize * count > length - programOffset) {
                return ElfInfo.error("invalid-program-headers");
            }
            byte[] program = new byte[entrySize];
            for (int index = 0; index < count; index++) {
                input.seek(programOffset + (long)index * entrySize);
                input.readFully(program);
                if (u32(program, 0) != PT_INTERP) continue;
                long offset = u64(program, 8);
                long size = u64(program, 32);
                if (size <= 0 || size > MAX_INTERPRETER_BYTES || offset < 0 || offset > length || size > length - offset) {
                    return ElfInfo.error("invalid-interpreter");
                }
                byte[] value = new byte[(int)size];
                input.seek(offset);
                input.readFully(value);
                int end = 0;
                while (end < value.length && value[end] != 0) end++;
                if (end == 0) return ElfInfo.error("empty-interpreter");
                String interpreter = new String(value, 0, end, "UTF-8");
                return new ElfInfo(true, type, machine, interpreter, new File(interpreter), null);
            }
            return new ElfInfo(true, type, machine, null, null, null);
        }
        catch (Exception e) {
            return ElfInfo.error(e.getClass().getSimpleName());
        }
    }

    private static String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 64L * 1024L * 1024L) return "<over-limit>";
                digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value));
            return result.toString();
        }
        catch (Exception e) { return "<" + e.getClass().getSimpleName() + ">"; }
    }

    private static boolean isSymlink(File file) {
        return file != null && Files.isSymbolicLink(file.toPath());
    }

    private static String symlinkTarget(File file) {
        if (!isSymlink(file)) return "<none>";
        try { return safe(Files.readSymbolicLink(file.toPath()).toString()); }
        catch (IOException e) { return "<error>"; }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8) |
            ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] data, int offset) {
        long result = 0;
        for (int index = 7; index >= 0; index--) result = (result << 8) | (data[offset + index] & 0xffL);
        return result;
    }

    private static String machineName(int machine) {
        return machine == EM_AARCH64 ? "AArch64" : String.valueOf(machine);
    }

    private static String safe(String value) {
        if (value == null) return "<null>";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class ElfInfo {
        private final boolean valid;
        private final int type;
        private final int machine;
        private final String interpreter;
        private final File interpreterFile;
        private final String error;

        private ElfInfo(boolean valid, int type, int machine, String interpreter, File interpreterFile, String error) {
            this.valid = valid;
            this.type = type;
            this.machine = machine;
            this.interpreter = interpreter;
            this.interpreterFile = interpreterFile;
            this.error = error;
        }

        private static ElfInfo error(String error) { return new ElfInfo(false, -1, -1, null, null, error); }
    }

    /* The rootfs is the parent of usr/local/bin/box64. */
    private static File findRootDir(File file) {
        File current = file;
        for (int i = 0; i < 4 && current != null; i++) current = current.getParentFile();
        return current;
    }
}
