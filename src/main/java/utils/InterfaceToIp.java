package utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.InvalidParameterException;
import java.util.Enumeration;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pt.unl.fct.di.novasys.babel.core.Babel;

/**
 * Resolves the process-wide bind address ({@code babel.address}) the node binds
 * and announces on.
 *
 * <p>Why this matters for the demo: for multicast auto-discovery (and for peers
 * to reach each other at all) every node must advertise a <em>reachable</em>
 * address — not {@code 127.0.0.1} and not {@code 0.0.0.0}. So the demo never
 * silently defaults to loopback. Instead {@link #resolveBindAddress(Properties)}
 * picks an address in this order:
 * <ol>
 *   <li>an explicit {@code babel.address} (the user's override always wins —
 *       including loopback, e.g. {@code babel.address=127.0.0.1} for running
 *       several nodes on one disconnected machine);</li>
 *   <li>the IPv4 address of the interface named in {@code babel.interface}
 *       (e.g. {@code babel.interface=en0} on macOS, {@code eth0} on Linux);</li>
 *   <li>auto-detection of the first reachable (non-loopback, up, non-virtual,
 *       non-point-to-point) IPv4 interface.</li>
 * </ol>
 * If none of these yields an address, it fails loudly and tells the operator to
 * pass {@code babel.interface} or {@code babel.address} explicitly.
 *
 * <p>The parameter keys {@code babel.address} / {@code babel.interface} /
 * {@code babel.port} are owned by babel-core ({@link Babel#PAR_DEFAULT_ADDRESS} /
 * {@link Babel#PAR_DEFAULT_INTERFACE} / {@link Babel#PAR_DEFAULT_PORT}); this
 * class references those constants rather than re-declaring the literals.
 */
public class InterfaceToIp {

    private static final Logger logger = LogManager.getLogger(InterfaceToIp.class);

    private InterfaceToIp() {
        // Utility class — not instantiable.
    }

    /** Returns the first non-loopback IPv4 address of {@code interfaceName}, or null if it has none / does not exist. */
    public static String getIpOfInterface(String interfaceName) throws SocketException {
        NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
        if (networkInterface == null) {
            return null;
        }
        return firstNonLoopbackIpv4(networkInterface);
    }

    /** First non-loopback IPv4 address bound to {@code networkInterface}, or null. */
    private static String firstNonLoopbackIpv4(NetworkInterface networkInterface) {
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
     * Auto-detect a reachable bind address: the IPv4 address of the first
     * non-loopback, up, non-virtual, non-point-to-point interface (the same
     * filter babel-core's discovery protocols use). Loopback is deliberately
     * never auto-selected. Returns null if no interface qualifies.
     */
    public static String autoDetectAddress() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface n = interfaces.nextElement();
            if (n.isLoopback() || n.isVirtual() || !n.isUp() || n.isPointToPoint()) {
                continue;
            }
            String ip = firstNonLoopbackIpv4(n);
            if (ip != null) {
                logger.info("Auto-detected bind interface '{}' -> {}", n.getName(), ip);
                return ip;
            }
        }
        return null;
    }

    /**
     * Resolve the process-wide bind address into {@code babel.address} (see the
     * class javadoc for the precedence). On return {@code babel.address} is
     * guaranteed to be set to a concrete IPv4 address.
     *
     * @throws InvalidParameterException if a named {@code babel.interface} has no
     *         usable address, or no address could be determined at all — in which
     *         case the operator must pass {@code babel.interface} or
     *         {@code babel.address} on the command line.
     */
    public static void resolveBindAddress(Properties props) throws SocketException, InvalidParameterException {
        // (1) An explicit address always wins — even loopback, if that is what the
        // user asked for (e.g. several nodes on one disconnected machine).
        if (props.getProperty(Babel.PAR_DEFAULT_ADDRESS) != null) {
            return;
        }

        // (2) A named interface: resolve it to its IPv4 address.
        String interfaceName = props.getProperty(Babel.PAR_DEFAULT_INTERFACE);
        if (interfaceName != null) {
            String ip = getIpOfInterface(interfaceName);
            if (ip == null) {
                throw new InvalidParameterException(
                        "Property '" + Babel.PAR_DEFAULT_INTERFACE + "' is set to '" + interfaceName
                                + "', but it has no usable (non-loopback IPv4) address.");
            }
            props.setProperty(Babel.PAR_DEFAULT_ADDRESS, ip);
            return;
        }

        // (3) Auto-detect a reachable interface.
        String ip = autoDetectAddress();
        if (ip == null) {
            throw new InvalidParameterException(
                    "Could not auto-detect a reachable network interface. Pass '"
                            + Babel.PAR_DEFAULT_INTERFACE + "=<nic>' (e.g. en0 / eth0), or '"
                            + Babel.PAR_DEFAULT_ADDRESS + "=<ip>' (e.g. 127.0.0.1 for several nodes on "
                            + "one machine) on the command line.");
        }
        props.setProperty(Babel.PAR_DEFAULT_ADDRESS, ip);
    }
}
