import java.net.UnknownHostException;
import java.util.Arrays;

/** Synthetic implementation of the Android service callback contract. */
public final class RelayVpnService {
    public boolean protect(int fd) {
        return fd >= 0;
    }

    public String[] resolveHost(String host) throws UnknownHostException {
        switch (host) {
            case "relay.invalid":
                return new String[] {"203.0.113.7", "2001:db8::7"};
            case "empty.invalid":
                return new String[0];
            case "malformed.invalid":
                return new String[] {"203.0.113.7", "not-an-ip"};
            case "null.invalid":
                return null;
            case "null-element.invalid":
                return new String[] {null};
            case "many.invalid":
                String[] addresses = new String[1024];
                Arrays.fill(addresses, "203.0.113.7");
                return addresses;
            case "stale.invalid":
                throw new UnknownHostException("underlying network changed: stale.invalid");
            default:
                throw new AssertionError("Unexpected hostname: " + host);
        }
    }
}
