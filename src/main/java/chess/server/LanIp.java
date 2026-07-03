package chess.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

public final class LanIp {

    private LanIp() {
    }

    /**
     * Best-effort LAN address: the first site-local IPv4 on an interface that is
     * up and not loopback/virtual. With several active interfaces the first one
     * wins — the address is shown in the UI so a wrong pick is visible.
     */
    public static String detect() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return "127.0.0.1"; // no LAN interface found; only this machine can join
    }
}
