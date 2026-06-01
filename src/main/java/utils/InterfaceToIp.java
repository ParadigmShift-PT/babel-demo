package utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.InvalidParameterException;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Small helper (borrowed from {@code babel-example}) that turns a network
 * interface name into the IPv4 address bound to it.
 *
 * <p>Why this matters for the demo: for multicast auto-discovery to work, each
 * node must announce a <em>reachable</em> address — not {@code 127.0.0.1} and not
 * {@code 0.0.0.0}. On a machine with several interfaces you tell the demo which
 * one to use by passing {@code interface=<name>} (e.g. {@code interface=en0} on
 * macOS, {@code interface=eth0} on Linux); this class resolves that to the
 * concrete IP and stashes it in the {@code address} property for the channel and
 * discovery to pick up.
 */
public class InterfaceToIp {

    /** Returns the first non-loopback IPv4 address of {@code interfaceName}, or null. */
    public static String getIpOfInterface(String interfaceName) throws SocketException {
        NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
        if (networkInterface == null) {
            return null;
        }
        Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
        while (inetAddresses.hasMoreElements()) {
            InetAddress currentAddress = inetAddresses.nextElement();
            if (currentAddress instanceof Inet4Address && !currentAddress.isLoopbackAddress()) {
                return currentAddress.getHostAddress();
            }
        }
        return null;
    }

    /**
     * If an {@code interface} property is set, resolve it to an IP and store it
     * under the {@code address} property. No-op if {@code interface} is absent.
     */
    public static void addInterfaceIp(Properties props) throws SocketException, InvalidParameterException {
        String interfaceName = props.getProperty("interface");
        if (interfaceName != null) {
            String ip = getIpOfInterface(interfaceName);
            if (ip != null) {
                props.setProperty("address", ip);
            } else {
                throw new InvalidParameterException(
                        "Property 'interface' is set to '" + interfaceName + "', but it has no usable IPv4 address");
            }
        }
    }
}
