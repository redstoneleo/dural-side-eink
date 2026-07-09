package com.example.myapp;

interface IShellService {
    String runCommand(String cmd);
    /**
     * 在 shell 进程（UID=2000）中创建带 TRUSTED 标志的虚拟显示器。
     * shell 天然拥有 ADD_TRUSTED_DISPLAY 权限，无需 pm grant，无需重启。
     * @return 创建成功的 displayId，失败返回 -1
     */
    int createTrustedVirtualDisplay(String name, int width, int height, int dpi, int flags, in Surface surface);
    void destroy();
}
