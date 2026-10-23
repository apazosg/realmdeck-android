// Sonda execve (experimento targetSdk): ¿puede la app ejecutar un binario
// que ella misma escribe en sus directorios?
//
// Compilada a mano con el NDK (sin CMake: no hay cmake en el SDK) como
// `libexec_probe.so` y empaquetada vía `jniLibs` (igual que el cdylib Rust).
// Ver `android/build-exec-probe.sh`.
//
// Método por directorio:
//  1. Copia `/system/bin/sh` (ELF del dispositivo, misma ABI) a
//     `<dir>/probe_exec_test` con 0755 (bytes escritos por la propia app).
//  2. `fork` + `execve(dest, [dest, "-c", "exit 42"], envp)`; el hijo solo
//     usa `execve` + `write` + `_exit` (seguro tras fork).
//  3. El padre recoge por un pipe el `errno` exacto si `execve` falla, o el
//     código de salida (42 = éxito) si el kernel lo ejecutó.
// Controles: C1 ejecuta `/system/bin/sh` directo (maquinaria fork/exec);
// C2 intenta ejecutar la primera `.so` de `nativeLibraryDir` (se espera
// ENOEXEC si el dir permite exec, EACCES si SELinux lo niega).

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <android/log.h>

#define TAG "ForeverJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Entorno del proceso para execve (declaración global: bionic lo expone).
extern char** environ;

namespace {

// Nombre simbólico del errno (los relevantes del experimento).
const char* errno_name(int e) {
    switch (e) {
        case EPERM: return "EPERM";
        case ENOENT: return "ENOENT";
        case EACCES: return "EACCES";
        case ENOEXEC: return "ENOEXEC";
        case EROFS: return "EROFS";
        case ETXTBSY: return "ETXTBSY";
        case EISDIR: return "EISDIR";
        case ENOTDIR: return "ENOTDIR";
        case ELOOP: return "ELOOP";
        case ENOMEM: return "ENOMEM";
        case EINVAL: return "EINVAL";
        case EBUSY: return "EBUSY";
        case EFAULT: return "EFAULT";
        case ENAMETOOLONG: return "ENAMETOOLONG";
        default: return "?";
    }
}

std::string errno_text(const char* ctx, int e) {
    char buf[256];
    // strerror es suficiente aquí (lado padre, no tras fork).
    snprintf(buf, sizeof(buf), "%s: %s (errno=%d %s)", ctx, strerror(e), e,
             errno_name(e));
    return std::string(buf);
}

// Primera ruta existente de la lista (el binario donante).
std::string pick_source() {
    const char* cands[] = {"/system/bin/sh", "/system/bin/toybox",
                           "/system/bin/ls", "/system/bin/true", nullptr};
    for (int i = 0; cands[i]; i++) {
        struct stat st;
        if (stat(cands[i], &st) == 0 && S_ISREG(st.st_mode)) return cands[i];
    }
    return "";
}

// Copia el fichero src a dst con modo 0755. Devuelve "OK(NB)" o el error.
std::string copy_exe(const std::string& src, const std::string& dst,
                     long* out_size) {
    ::unlink(dst.c_str());
    int in = ::open(src.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) return errno_text("open_src falló", errno);
    int out = ::open(dst.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
    if (out < 0) {
        int e = errno;
        ::close(in);
        return errno_text("open_dst falló", e);
    }
    char buf[32768];
    long total = 0;
    while (true) {
        ssize_t n = ::read(in, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            int e = errno;
            ::close(in);
            ::close(out);
            return errno_text("read_src falló", e);
        }
        if (n == 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = ::write(out, buf + off, (size_t)(n - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                int e = errno;
                ::close(in);
                ::close(out);
                return errno_text("write_dst falló", e);
            }
            off += w;
            total += w;
        }
    }
    ::close(in);
    ::close(out);
    if (::chmod(dst.c_str(), 0755) != 0) {
        return errno_text("chmod 0755 falló", errno);
    }
    if (out_size) *out_size = total;
    char ok[64];
    snprintf(ok, sizeof(ok), "OK(%ldB)", total);
    return std::string(ok);
}

// Resultado de un intento de ejecución.
struct ExecResult {
    bool ok = false;   // true si execve tuvo éxito (el hijo corrió)
    int exit_code = -1;
    int child_errno = 0;
    bool timed_out = false;
    std::string signal_info;
};

// Ejecuta `path` con argv dados; comunica el errno del hijo por pipe.
// Timeout en ms para el hijo (si cuelga, SIGKILL y timed_out=true).
ExecResult try_exec(const char* path, char* const argv[], int timeout_ms) {
    ExecResult r;
    int pfd[2];
    if (::pipe2(pfd, O_CLOEXEC) != 0) {
        // Sin pipe no se puede distinguir el errno: se informa como errno.
        r.child_errno = errno;
        return r;
    }
    pid_t pid = ::fork();
    if (pid < 0) {
        r.child_errno = errno;
        ::close(pfd[0]);
        ::close(pfd[1]);
        // Marca especial: fork falló (errno en child_errno, ok=false).
        return r;
    }
    if (pid == 0) {
        // Hijo: solo execve + informe de errno + _exit.
        ::close(pfd[0]);
        ::execve(path, argv, environ);
        int e = errno;
        // Escribe el errno al padre (4 bytes) e ignora errores.
        (void)::write(pfd[1], &e, sizeof(e));
        ::close(pfd[1]);
        ::_exit(127);
    }
    // Padre.
    ::close(pfd[1]);
    // Espera al hijo con sondeo (no bloqueante) hasta timeout_ms.
    int status = 0;
    bool dead = false;
    int waited = 0;
    while (waited < timeout_ms) {
        pid_t w = ::waitpid(pid, &status, WNOHANG);
        if (w == pid) {
            dead = true;
            break;
        }
        if (w < 0 && errno != EINTR) break;
        ::usleep(50 * 1000);
        waited += 50;
    }
    // ¿El hijo escribió un errno por el pipe? (exec falló).
    int child_errno = 0;
    ssize_t n = ::read(pfd[0], &child_errno, sizeof(child_errno));
    ::close(pfd[0]);
    if (!dead) {
        ::kill(pid, SIGKILL);
        ::waitpid(pid, &status, 0);
        r.timed_out = true;
        // Si además hay errno, se conserva.
        if (n == (ssize_t)sizeof(child_errno)) r.child_errno = child_errno;
        return r;
    }
    if (n == (ssize_t)sizeof(child_errno)) {
        // execve falló en el hijo.
        r.ok = false;
        r.child_errno = child_errno;
        return r;
    }
    // execve tuvo éxito: el hijo corrió el programa.
    r.ok = true;
    r.child_errno = 0;
    if (WIFEXITED(status)) {
        r.exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        char b[64];
        snprintf(b, sizeof(b), "señal=%d", WTERMSIG(status));
        r.signal_info = b;
    }
    return r;
}

std::string fmt_exec(const ExecResult& r) {
    char b[256];
    if (r.ok) {
        if (!r.signal_info.empty())
            snprintf(b, sizeof(b), "exec=OK pero %s (execve SÍ permitió arrancar)",
                     r.signal_info.c_str());
        else
            snprintf(b, sizeof(b), "exec=OK exit=%d (el kernel EJECUTÓ el binario)",
                     r.exit_code);
        return std::string(b);
    }
    if (r.timed_out)
        return "exec=TIMEOUT (el hijo no salió en 5 s; matado con SIGKILL)";
    if (r.child_errno == 0 && r.exit_code == -1)
        return "exec=FORK_FALLÓ (no se pudo crear el hijo)";
    return errno_text("exec=FALLÓ", r.child_errno);
}

// Sonda completa de un directorio escribible: copia + exec.
std::string probe_dir(const char* label, const std::string& dir,
                      const std::string& src) {
    if (dir.empty()) return std::string("dir=") + label + " SKIP (null/no disponible)";
    std::string dst = dir + "/probe_exec_test";
    long sz = 0;
    std::string wr = copy_exe(src, dst, &sz);
    std::string line = std::string("dir=") + label + " path=" + dst + " src=" + src +
                       " write=" + wr;
    if (wr.rfind("OK(", 0) != 0) {
        // Si ni siquiera se pudo escribir, se intenta el exec igual para
        // registrar su errno (suele ser ENOENT tras unlink).
        LOGI("%s", line.c_str());
        return line + " exec=no_intentado";
    }
    char* argv[] = {(char*)dst.c_str(), (char*)"-c", (char*)"exit 42", nullptr};
    ExecResult r = try_exec(dst.c_str(), argv, 5000);
    line += " " + fmt_exec(r);
    LOGI("%s", line.c_str());
    ::unlink(dst.c_str());
    return line;
}

// Primera .so encontrada en nativeLibraryDir (control positivo C2).
std::string first_so(const std::string& dir) {
    DIR* d = ::opendir(dir.c_str());
    if (!d) return "";
    std::string found;
    while (dirent* e = ::readdir(d)) {
        std::string n = e->d_name;
        if (n.size() > 3 && n.compare(n.size() - 3, 3, ".so") == 0) {
            found = dir + "/" + n;
            break;
        }
    }
    ::closedir(d);
    return found;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

}  // namespace

// com.wowforever.launcher.ExecProbe.runExecProbe
extern "C" JNIEXPORT jstring JNICALL
Java_com_wowforever_launcher_ExecProbe_runExecProbe(
    JNIEnv* env, jclass, jstring jFiles, jstring jCodeCache, jstring jExt,
    jstring jNativeLib) {
    std::string files = jstr(env, jFiles);
    std::string cache = jstr(env, jCodeCache);
    std::string ext = jstr(env, jExt);
    std::string nativelib = jstr(env, jNativeLib);

    std::string src = pick_source();
    std::string out = "exec_probe src=" + (src.empty() ? std::string("(ninguno)") : src) + "\n";
    LOGI("exec_probe src=%s files=%s cache=%s ext=%s nativelib=%s", src.c_str(),
         files.c_str(), cache.c_str(), ext.c_str(), nativelib.c_str());

    if (src.empty()) {
        out += "result=FAIL cause=no hay binario donante en /system/bin\n";
        return env->NewStringUTF(out.c_str());
    }

    // a/b/c: los tres directorios escribibles. d: nativeLibraryDir (se espera
    // que la escritura falle: no es escribible; aun así se intenta).
    std::string la = probe_dir("filesDir", files, src);
    std::string lb = probe_dir("codeCacheDir", cache, src);
    std::string lc = probe_dir("externalFilesDir", ext, src);
    std::string ld = probe_dir("nativeLibraryDir", nativelib, src);
    out += la + "\n" + lb + "\n" + lc + "\n" + ld + "\n";

    // C1: control de maquinaria (ejecuta el donante in situ).
    {
        char* argv[] = {(char*)src.c_str(), (char*)"-c", (char*)"exit 7", nullptr};
        ExecResult r = try_exec(src.c_str(), argv, 5000);
        std::string l = std::string("control=C1_directa path=") + src + " " + fmt_exec(r);
        LOGI("%s", l.c_str());
        out += l + "\n";
    }
    // C2: control positivo del dir ejecutable (una .so existente: si el dir
    // permite exec, el fallo es ENOEXEC; si SELinux lo niega, EACCES).
    {
        std::string so = first_so(nativelib);
        if (so.empty()) {
            std::string l = "control=C2_so SKIP (sin .so en nativeLibraryDir)";
            LOGI("%s", l.c_str());
            out += l + "\n";
        } else {
            char* argv[] = {(char*)so.c_str(), nullptr};
            ExecResult r = try_exec(so.c_str(), argv, 5000);
            std::string l = std::string("control=C2_so path=") + so + " " + fmt_exec(r);
            LOGI("%s", l.c_str());
            out += l + "\n";
        }
    }
    return env->NewStringUTF(out.c_str());
}
