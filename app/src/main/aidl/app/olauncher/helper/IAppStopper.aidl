package app.olauncher.helper;
interface IAppStopper {
    int stopApps(in String[] packages, int userId) = 1;
    void destroy() = 16777114;
}
