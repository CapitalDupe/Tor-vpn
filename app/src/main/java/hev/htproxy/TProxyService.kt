package hev.htproxy

/**
 * JNI shim for hev-socks5-tunnel. The package and class name are fixed: the prebuilt AAR
 * registers its native methods against hev/htproxy/TProxyService.
 */
object TProxyService {
    external fun TProxyStartService(config_path: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
