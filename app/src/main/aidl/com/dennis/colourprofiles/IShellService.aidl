package com.dennis.colourprofiles;

interface IShellService {
    // Reserved by Shizuku for stopping the service.
    void destroy() = 16777114;

    String applyMode(int mode, boolean useMdnie) = 1;
    int readMode() = 2;
    String enableAccessibility(String component) = 3;
    String diagnose() = 4;
}
