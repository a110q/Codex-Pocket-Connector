#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shlobj.h>
#include <wchar.h>
#include <stdio.h>
#include <stdlib.h>
#ifndef POCKET_VERSION
#define POCKET_VERSION L"0.7.1"
#endif

static HANDLE job;
static HWND splash;
static BOOL cancelled=FALSE;
static void fail(const wchar_t *message) { MessageBoxW(NULL,message,L"Codex Pocket",MB_OK|MB_ICONERROR); }
static BOOL file_exists(const wchar_t *p) { DWORD a=GetFileAttributesW(p); return a!=INVALID_FILE_ATTRIBUTES && !(a&FILE_ATTRIBUTE_DIRECTORY); }
static BOOL join(wchar_t *out, size_t count, const wchar_t *left,const wchar_t *right) {
    int n=swprintf(out,count,L"%ls\\%ls",left,right);return n>=0&&(size_t)n<count;
}
static BOOL write_bytes(const wchar_t *p,const void *data,DWORD length) {
    HANDLE f=CreateFileW(p,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL); if(f==INVALID_HANDLE_VALUE)return FALSE;
    DWORD written=0;BOOL ok=WriteFile(f,data,length,&written,NULL)&&written==length;CloseHandle(f);return ok;
}
static LRESULT CALLBACK splash_proc(HWND window,UINT message,WPARAM w,LPARAM l) {
    if(message==WM_CLOSE){cancelled=TRUE;if(job)TerminateJobObject(job,1);DestroyWindow(window);splash=NULL;return 0;}
    return DefWindowProcW(window,message,w,l);
}
static void show_splash(void) {
    WNDCLASSW cls={0};cls.lpfnWndProc=splash_proc;cls.hInstance=GetModuleHandleW(NULL);cls.lpszClassName=L"CodexPocketPreparing";cls.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);cls.hIcon=LoadIconW(cls.hInstance,MAKEINTRESOURCEW(1));RegisterClassW(&cls);
    splash=CreateWindowExW(0,cls.lpszClassName,L"Codex Pocket",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU,(GetSystemMetrics(SM_CXSCREEN)-460)/2,(GetSystemMetrics(SM_CYSCREEN)-170)/2,460,170,NULL,NULL,cls.hInstance,NULL);
    HWND title=CreateWindowW(L"STATIC",L"正在准备 Codex Pocket…",WS_CHILD|WS_VISIBLE|SS_CENTER,20,35,410,30,splash,NULL,cls.hInstance,NULL);
    HWND note=CreateWindowW(L"STATIC",L"首次打开需要片刻，请稍候。",WS_CHILD|WS_VISIBLE|SS_CENTER,20,78,410,24,splash,NULL,cls.hInstance,NULL);
    HFONT font=(HFONT)GetStockObject(DEFAULT_GUI_FONT);SendMessageW(title,WM_SETFONT,(WPARAM)font,TRUE);SendMessageW(note,WM_SETFONT,(WPARAM)font,TRUE);ShowWindow(splash,SW_SHOW);UpdateWindow(splash);
}
// Every PowerShell, Node, Codex and WebView descendant stays in this job. The
// kernel releases all children and listeners if the EXE is closed or killed.
static DWORD run_owned(wchar_t *command, const wchar_t *cwd) {
    wchar_t system[MAX_PATH],powershell[MAX_PATH];GetSystemDirectoryW(system,MAX_PATH);
    if(!join(powershell,MAX_PATH,system,L"WindowsPowerShell\\v1.0\\powershell.exe"))return 1;
    STARTUPINFOW startup={0};startup.cb=sizeof(startup);PROCESS_INFORMATION child={0};
    if(!CreateProcessW(powershell,command,NULL,NULL,FALSE,CREATE_SUSPENDED|CREATE_NO_WINDOW,NULL,cwd,&startup,&child))return 1;
    if(!AssignProcessToJobObject(job,child.hProcess)){TerminateProcess(child.hProcess,1);CloseHandle(child.hThread);CloseHandle(child.hProcess);return 1;}
    ResumeThread(child.hThread);CloseHandle(child.hThread);
    for(;;){DWORD result=MsgWaitForMultipleObjects(1,&child.hProcess,FALSE,INFINITE,QS_ALLINPUT);if(result==WAIT_OBJECT_0)break;if(result==WAIT_FAILED){TerminateJobObject(job,1);break;}MSG message;while(PeekMessageW(&message,NULL,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}}
    DWORD code=1;GetExitCodeProcess(child.hProcess,&code);CloseHandle(child.hProcess);return code;
}
#ifdef POCKET_EMBEDDED
static BOOL extract_app(wchar_t *root,size_t count) {
    wchar_t local[MAX_PATH],base[32768],target[32768],stage[32768],marker[32768];
    if(FAILED(SHGetFolderPathW(NULL,CSIDL_LOCAL_APPDATA,NULL,SHGFP_TYPE_CURRENT,local)))return FALSE;
    if(!join(base,32768,local,L"Programs\\Codex Pocket Connector"))return FALSE;
    if(SHCreateDirectoryExW(NULL,base,NULL)!=ERROR_SUCCESS && GetFileAttributesW(base)==INVALID_FILE_ATTRIBUTES)return FALSE;
    if(!join(target,32768,base,POCKET_VERSION)||!join(marker,32768,target,L".complete"))return FALSE;
    char expected[]=POCKET_PAYLOAD_SHA;
    HANDLE f=CreateFileW(marker,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    if(f!=INVALID_HANDLE_VALUE){char content[65]={0};DWORD n=0;BOOL ok=ReadFile(f,content,64,&n,NULL);CloseHandle(f);if(ok&&n==64&&!memcmp(expected,content,64))return join(root,count,target,L"app");}
    swprintf(stage,32768,L"%ls\\.prepare-%lu-%llu",base,GetCurrentProcessId(),GetTickCount64());
    if(!CreateDirectoryW(stage,NULL))return FALSE;
    wchar_t archive[32768],script[32768];join(archive,32768,stage,L"payload.zip");join(script,32768,stage,L"extract.ps1");
    HRSRC resource=FindResourceW(NULL,MAKEINTRESOURCEW(101),RT_RCDATA);if(!resource)return FALSE;
    HGLOBAL loaded=LoadResource(NULL,resource);DWORD size=SizeofResource(NULL,resource);void *bytes=LockResource(loaded);if(!bytes||!size||!write_bytes(archive,bytes,size))return FALSE;
    const char ps[]="\xef\xbb\xbfparam([string]$Archive,[string]$Destination)\r\n$ErrorActionPreference='Stop'\r\nAdd-Type -AssemblyName System.IO.Compression.FileSystem\r\n[IO.Compression.ZipFile]::ExtractToDirectory($Archive,$Destination)\r\n";
    if(!write_bytes(script,ps,sizeof(ps)-1))return FALSE;
    wchar_t *command=calloc(100000,sizeof(wchar_t));if(!command)return FALSE;
    swprintf(command,100000,L"powershell.exe -STA -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%ls\" -Archive \"%ls\" -Destination \"%ls\"",script,archive,stage);
    DWORD code=run_owned(command,stage);free(command);if(code!=0||cancelled)return FALSE;
    DeleteFileW(archive);DeleteFileW(script);
    wchar_t ready[32768];join(ready,32768,stage,L".complete");if(!write_bytes(ready,expected,64))return FALSE;
    if(GetFileAttributesW(target)!=INVALID_FILE_ATTRIBUTES){wchar_t old[32768];swprintf(old,32768,L"%ls.incomplete-%llu",target,GetTickCount64());if(!MoveFileW(target,old))return FALSE;}
    if(!MoveFileW(stage,target))return FALSE;
    return join(root,count,target,L"app");
}
#endif
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,LPWSTR args,int show) {
    (void)instance;(void)previous;(void)args;(void)show;
    HANDLE mutex=CreateMutexW(NULL,TRUE,L"Local\\CodexPocket-EXE-Launcher");
    if(!mutex)return 1;
    if(GetLastError()==ERROR_ALREADY_EXISTS){HWND window=FindWindowW(NULL,L"Codex Pocket");if(window){ShowWindow(window,SW_RESTORE);SetForegroundWindow(window);}else MessageBoxW(NULL,L"Codex Pocket 已经运行，请切回已有窗口。",L"Codex Pocket",MB_OK);CloseHandle(mutex);return 0;}
    job=CreateJobObjectW(NULL,NULL);JOBOBJECT_EXTENDED_LIMIT_INFORMATION info={0};info.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if(!job||!SetInformationJobObject(job,JobObjectExtendedLimitInformation,&info,sizeof(info))){fail(L"无法建立连接服务的退出保护，请重新打开软件。");if(job)CloseHandle(job);CloseHandle(mutex);return 1;}
    wchar_t root[32768],script[32768];
#ifdef POCKET_EMBEDDED
    show_splash();
    if(!extract_app(root,32768)){if(splash)DestroyWindow(splash);if(!cancelled)fail(L"准备应用文件失败，请检查磁盘空间后重试，或使用免安装 ZIP。");CloseHandle(job);CloseHandle(mutex);return 1;}
    if(splash)DestroyWindow(splash);splash=NULL;
#else
    DWORD n=GetModuleFileNameW(NULL,root,32768);if(!n||n>=32768){CloseHandle(job);CloseHandle(mutex);return 1;}
    wchar_t *last=wcsrchr(root,L'\\');if(!last){CloseHandle(job);CloseHandle(mutex);return 1;}*last=0;
    join(script,32768,root,L"scripts\\native-window.ps1");
    if(!file_exists(script)){wchar_t app[32768];join(app,32768,root,L"app");wcscpy(root,app);}
#endif
    join(script,32768,root,L"scripts\\native-window.ps1");
    if(!file_exists(script)){fail(L"应用文件不完整。请解压整个 ZIP 后打开 EXE，或重新下载单文件 EXE。");CloseHandle(job);CloseHandle(mutex);return 1;}
    wchar_t *command=calloc(70000,sizeof(wchar_t));if(!command){CloseHandle(job);CloseHandle(mutex);return 1;}
    swprintf(command,70000,L"powershell.exe -STA -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%ls\"",script);
    DWORD code=run_owned(command,root);free(command);
    CloseHandle(job);job=NULL;ReleaseMutex(mutex);CloseHandle(mutex);
    return code==0?0:1;
}
