package io.github.mbanucu.wirelessadbkeeper;

/**
 * One saved network, as the store reads it back.
 *
 * <p>The id is opaque and stable: a rename edits the SSID, it does not create a
 * new profile. The SSID is not the id because an SSID may contain any character,
 * including the comma the ordered id list uses.
 */
public final class Profile {

    private final String id;
    private final String ssid;
    private final String security;

    public Profile(String id, String ssid, String security) {
        this.id = id == null ? "" : id;
        this.ssid = ssid == null ? "" : ssid;
        this.security = security == null ? "" : security;
    }

    public String id() {
        return id;
    }

    public String ssid() {
        return ssid;
    }

    /** auto|wpa2|wpa3|owe|open; empty means "decide from the scan". */
    public String security() {
        return security;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Profile)) return false;
        Profile p = (Profile) other;
        return id.equals(p.id) && ssid.equals(p.ssid) && security.equals(p.security);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + ssid.hashCode();
        result = 31 * result + security.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Profile{" + id + " " + ssid + " (" + security + ")}";
    }
}
