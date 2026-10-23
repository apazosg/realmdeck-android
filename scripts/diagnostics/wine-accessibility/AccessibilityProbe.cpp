// Metadata-only MSAA experiment. Never reads accessible names, values or text.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <oleacc.h>
#include <stdio.h>
#include <stdint.h>

static DWORD target_pid;
static HANDLE target_process;
static unsigned windows_seen, nodes_seen, errors_seen;
static bool truncated;
static const unsigned MAX_WINDOWS = 16, MAX_NODES = 128, MAX_DEPTH = 8;

static bool alive()
{
    return WaitForSingleObject(target_process, 0) == WAIT_TIMEOUT;
}

static bool owned(HWND hwnd)
{
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    return hwnd && pid == target_pid && alive();
}

static void error_code(const char *stage, HRESULT hr)
{
    ++errors_seen;
    printf("{\"event\":\"error\",\"stage\":\"%s\",\"hresult\":%lu}\n",
           stage, static_cast<unsigned long>(hr));
}

// Terminate only this diagnostic if a provider blocks a synchronous COM call.
static DWORD WINAPI watchdog(void *)
{
    Sleep(10000);
    TerminateProcess(GetCurrentProcess(), 4);
    return 0;
}

static void visit(IAccessible *acc, VARIANT child, unsigned parent, unsigned depth)
{
    if (nodes_seen >= MAX_NODES || depth > MAX_DEPTH) {
        truncated = true;
        return;
    }
    HWND hwnd = NULL;
    HRESULT hr = WindowFromAccessibleObject(acc, &hwnd);
    if (FAILED(hr) || !owned(hwnd)) {
        error_code("node_identity", FAILED(hr) ? hr : E_ACCESSDENIED);
        return;
    }
    unsigned id = ++nodes_seen;
    VARIANT role, state;
    VariantInit(&role);
    VariantInit(&state);
    HRESULT role_hr = acc->get_accRole(child, &role);
    HRESULT state_hr = acc->get_accState(child, &state);
    LONG x = 0, y = 0, width = 0, height = 0;
    HRESULT rect_hr = acc->accLocation(&x, &y, &width, &height, child);
    const bool role_ok = SUCCEEDED(role_hr) && role.vt == VT_I4;
    const bool state_ok = SUCCEEDED(state_hr) && state.vt == VT_I4;
    const LONG flags = state_ok ? state.lVal : 0;
    // Only fixed numeric fields; even a provider's unexpected string is not logged.
    printf("{\"event\":\"node\",\"id\":%u,\"parent\":%u,\"depth\":%u,"
           "\"hwnd\":%llu,\"role_valid\":%d,\"role\":%ld,\"state_valid\":%d,"
           "\"focused\":%d,\"password\":%d,\"invisible\":%d,\"unavailable\":%d,"
           "\"rect_valid\":%d,\"x\":%ld,\"y\":%ld,\"width\":%ld,\"height\":%ld}\n",
           id, parent, depth, static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(hwnd)),
           role_ok, role_ok ? role.lVal : 0, state_ok,
           !!(flags & STATE_SYSTEM_FOCUSED), !!(flags & STATE_SYSTEM_PROTECTED),
           !!(flags & STATE_SYSTEM_INVISIBLE), !!(flags & STATE_SYSTEM_UNAVAILABLE),
           SUCCEEDED(rect_hr), x, y, width, height);
    VariantClear(&role);
    VariantClear(&state);
    if (FAILED(role_hr)) error_code("role", role_hr);
    if (FAILED(state_hr)) error_code("state", state_hr);
    if (FAILED(rect_hr)) error_code("geometry", rect_hr);
    if (child.lVal != CHILDID_SELF) return; // Simple children have no child tree.

    LONG count = 0;
    hr = acc->get_accChildCount(&count);
    if (FAILED(hr) || count < 0) {
        error_code("child_count", FAILED(hr) ? hr : E_UNEXPECTED);
        return;
    }
    if (!count) return;
    if (depth == MAX_DEPTH || nodes_seen >= MAX_NODES) {
        truncated = true;
        return;
    }
    // Bound provider output allocation regardless of its reported child count.
    VARIANT children[MAX_NODES];
    for (unsigned i = 0; i < MAX_NODES; ++i) VariantInit(&children[i]);
    LONG received = 0;
    const LONG requested = count < static_cast<LONG>(MAX_NODES - nodes_seen)
        ? count : static_cast<LONG>(MAX_NODES - nodes_seen);
    if (count > requested) truncated = true;
    hr = AccessibleChildren(acc, 0, requested, children, &received);
    if (FAILED(hr) || received < 0 || received > requested) {
        error_code("children", FAILED(hr) ? hr : E_UNEXPECTED);
    } else {
        for (LONG i = 0; i < received; ++i) {
            if (children[i].vt == VT_I4 && children[i].lVal > 0) {
                visit(acc, children[i], id, depth + 1);
            } else if (children[i].vt == VT_DISPATCH && children[i].pdispVal) {
                IAccessible *next = NULL;
                HRESULT qi = children[i].pdispVal->QueryInterface(IID_IAccessible,
                                                                reinterpret_cast<void **>(&next));
                if (SUCCEEDED(qi) && next) {
                    VARIANT self;
                    VariantInit(&self);
                    self.vt = VT_I4;
                    self.lVal = CHILDID_SELF;
                    visit(next, self, id, depth + 1);
                    next->Release();
                } else error_code("child_interface", FAILED(qi) ? qi : E_UNEXPECTED);
            } else error_code("child_type", E_UNEXPECTED);
        }
    }
    for (unsigned i = 0; i < MAX_NODES; ++i) VariantClear(&children[i]);
}

static BOOL CALLBACK window_callback(HWND hwnd, LPARAM)
{
    if (!alive()) return FALSE;
    if (!owned(hwnd)) return TRUE;
    if (windows_seen >= MAX_WINDOWS || nodes_seen >= MAX_NODES) {
        truncated = true;
        return FALSE;
    }
    ++windows_seen;
    const DWORD thread = GetWindowThreadProcessId(hwnd, NULL);
    GUITHREADINFO info = {};
    info.cbSize = sizeof(info);
    const bool gui_ok = GetGUIThreadInfo(thread, &info) != FALSE;
    const HWND focus = gui_ok && owned(info.hwndFocus) ? info.hwndFocus : NULL;
    printf("{\"event\":\"window\",\"hwnd\":%llu,\"visible\":%d,\"enabled\":%d,"
           "\"gui_valid\":%d,\"focus_hwnd\":%llu}\n",
           static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(hwnd)),
           !!IsWindowVisible(hwnd), !!IsWindowEnabled(hwnd), gui_ok,
           static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(focus)));
    IAccessible *acc = NULL;
    HRESULT hr = AccessibleObjectFromWindow(hwnd, OBJID_CLIENT, IID_IAccessible,
                                           reinterpret_cast<void **>(&acc));
    if (FAILED(hr) || !acc) {
        error_code("root", FAILED(hr) ? hr : E_UNEXPECTED);
        return TRUE;
    }
    VARIANT self;
    VariantInit(&self);
    self.vt = VT_I4;
    self.lVal = CHILDID_SELF;
    visit(acc, self, 0, 0);
    acc->Release();
    return TRUE;
}

int main(int argc, char **argv)
{
    // Strict decimal Windows PID only; never echo rejected arguments.
    if (argc != 2 || !argv[1][0]) return 2;
    uint64_t pid = 0;
    for (const char *p = argv[1]; *p; ++p) {
        if (*p < '0' || *p > '9') return 2;
        pid = pid * 10 + static_cast<unsigned>(*p - '0');
        if (pid > MAXDWORD) return 2;
    }
    if (!pid || pid == GetCurrentProcessId()) return 2;
    target_pid = static_cast<DWORD>(pid);
    target_process = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, target_pid);
    if (!target_process) return 3;
    setvbuf(stdout, NULL, _IONBF, 0);
    HANDLE timer = CreateThread(NULL, 0, watchdog, NULL, 0, NULL);
    if (!timer) { CloseHandle(target_process); return 3; }
    CloseHandle(timer);
    HRESULT hr = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) { error_code("com", hr); CloseHandle(target_process); return 3; }
    HWND foreground_start = GetForegroundWindow();
    printf("{\"event\":\"start\",\"schema\":1,\"pid\":%lu,\"backend\":\"msaa\","
           "\"timeout_ms\":10000,\"max_nodes\":128,\"max_depth\":8,\"max_windows\":16,"
           "\"foreground_owned\":%d}\n", target_pid, owned(foreground_start));
    const BOOL enumerated = EnumWindows(window_callback, 0);
    const bool process_alive = alive();
    const HWND foreground_end = GetForegroundWindow();
    const bool foreground_unchanged = foreground_start == foreground_end;
    printf("{\"event\":\"end\",\"windows\":%u,\"nodes\":%u,\"errors\":%u,"
           "\"truncated\":%d,\"enumeration_returned_true\":%d,\"process_alive\":%d,"
           "\"foreground_unchanged\":%d,\"foreground_owned\":%d,\"readiness_proven\":false}\n",
           windows_seen, nodes_seen, errors_seen, truncated, !!enumerated,
           process_alive, foreground_unchanged, owned(foreground_end));
    CoUninitialize();
    CloseHandle(target_process);
    return process_alive && enumerated && !truncated && !errors_seen ? 0 : 3;
}
