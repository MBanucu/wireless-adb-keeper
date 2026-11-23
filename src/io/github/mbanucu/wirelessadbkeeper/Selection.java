package io.github.mbanucu.wirelessadbkeeper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Which saved network a local-only request should try, without Android types.
 *
 * <p>The profile list is the priority order: the first profile wins unless its
 * SSID is absent from the scan and a later profile is present. A network that is
 * not in the scan is not requested; when nothing is present, an existing link is
 * kept instead of tearing it down for a network that is not there.
 *
 * <p>One cycle may try at most {@link #MAX_PROFILES_PER_CYCLE} present profiles.
 * After a failure the caller passes the ids it already tried back in, so the
 * next choice is the next present profile rather than the same one again.
 * Equal candidates (the same SSID saved twice, which the store refuses but a
 * direct caller can still construct) are ordered by id, so a tie is stable.
 */
public final class Selection {

    /** The attempt budget is three, so one cycle cannot try more profiles. */
    public static final int MAX_PROFILES_PER_CYCLE = 3;

    private Selection() {
    }

    /** One choice: a profile to request, an existing link to keep, or neither. */
    public static final class Choice {
        private final Profile profile;
        private final Profile link;
        private final List<Profile> tried;
        private final List<Profile> attempted;

        private Choice(Profile profile, Profile link, List<Profile> tried,
                       List<Profile> attempted) {
            this.profile = profile;
            this.link = link;
            this.tried = Collections.unmodifiableList(new ArrayList<>(tried));
            this.attempted = Collections.unmodifiableList(new ArrayList<>(attempted));
        }

        static Choice chosen(Profile profile, List<Profile> tried,
                             List<Profile> attempted) {
            return new Choice(profile, null, tried, attempted);
        }

        static Choice kept(Profile link, List<Profile> tried,
                           List<Profile> attempted) {
            return new Choice(null, link, tried, attempted);
        }

        static Choice none(List<Profile> tried, List<Profile> attempted) {
            return new Choice(null, null, tried, attempted);
        }

        /** The profile to request, or null when the link is kept or none is present. */
        public Profile profile() {
            return profile;
        }

        /** The profile matching the kept link, when one of them is current. */
        public Profile link() {
            return link;
        }

        public boolean chosen() {
            return profile != null;
        }

        /** True when nothing in the scan matched but a link was already up. */
        public boolean kept() {
            return profile == null && link != null;
        }

        /** True when there is no profile to request and no link to keep. */
        public boolean noCandidate() {
            return profile == null && link == null;
        }

        /** Every profile that was checked against the scan, in priority order. */
        public List<Profile> tried() {
            return tried;
        }

        /** The present profiles this cycle already attempted before this choice. */
        public List<Profile> attempted() {
            return attempted;
        }

        /** `ssid (id)` for each tried profile, for a status line or a journal. */
        public String triedText() {
            return names(tried);
        }

        /** `ssid (id)` for each already-attempted profile. */
        public String attemptedText() {
            return names(attempted);
        }
    }

    /**
     * The first present profile in priority order, or an explicit no-candidate
     * when nothing is present and no link is up. The three-argument form has no
     * profile failures yet.
     */
    public static Choice choose(List<Profile> profiles, Collection<String> scanSsids,
                                String currentLink) {
        return choose(profiles, scanSsids, currentLink, Collections.<String>emptyList());
    }

    /**
     * The next present profile whose id is not in {@code triedIds}. When the
     * present profiles are used up, a current link is kept; otherwise the
     * result is no-candidate and names every profile that was checked.
     */
    public static Choice choose(List<Profile> profiles, Collection<String> scanSsids,
                                String currentLink, Collection<String> triedIds) {
        List<Profile> all = profiles == null
                ? Collections.<Profile>emptyList() : profiles;
        List<Profile> present = candidates(all, scanSsids);
        List<Profile> attempted = new ArrayList<>();
        for (int i = 0; i < present.size(); i++) {
            Profile profile = present.get(i);
            if (contains(triedIds, profile.id())) {
                attempted.add(profile);
                continue;
            }
            List<Profile> tried = new ArrayList<>(all);
            return Choice.chosen(profile, tried, attempted);
        }

        List<Profile> tried = new ArrayList<>(all);
        if (currentLink != null && !currentLink.isEmpty()) {
            return Choice.kept(profileWithSsid(all, currentLink), tried, attempted);
        }
        return Choice.none(tried, attempted);
    }

    /**
     * The present profiles in priority order, at most
     * {@link #MAX_PROFILES_PER_CYCLE} of them. Two profiles for the same SSID
     * cannot both be requested; the lexicographically smaller id wins.
     */
    public static List<Profile> candidates(List<Profile> profiles,
                                           Collection<String> scanSsids) {
        List<Profile> present = new ArrayList<>();
        if (profiles == null) return present;
        for (Profile profile : profiles) {
            if (profile == null || profile.ssid().isEmpty()
                    || !contains(scanSsids, profile.ssid())) {
                continue;
            }
            int sameSsid = indexOfSsid(present, profile.ssid());
            if (sameSsid < 0) {
                present.add(profile);
            } else if (profile.id().compareTo(present.get(sameSsid).id()) < 0) {
                present.set(sameSsid, profile);
            }
        }
        if (present.size() <= MAX_PROFILES_PER_CYCLE) return present;
        return new ArrayList<>(present.subList(0, MAX_PROFILES_PER_CYCLE));
    }

    /** The display form used by the status line and the journal: `lab (p2)`. */
    public static String label(Profile profile) {
        if (profile == null) return "";
        return profile.ssid() + " (" + profile.id() + ")";
    }

    private static Profile profileWithSsid(List<Profile> profiles, String ssid) {
        for (Profile profile : profiles) {
            if (profile.ssid().equals(ssid)) return profile;
        }
        return null;
    }

    private static int indexOfSsid(List<Profile> profiles, String ssid) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).ssid().equals(ssid)) return i;
        }
        return -1;
    }

    private static boolean contains(Collection<String> values, String value) {
        if (values == null || value == null) return false;
        for (String candidate : values) {
            if (value.equals(candidate)) return true;
        }
        return false;
    }

    private static String names(List<Profile> profiles) {
        StringBuilder out = new StringBuilder();
        for (Profile profile : profiles) {
            if (out.length() > 0) out.append(", ");
            out.append(label(profile));
        }
        return out.toString();
    }
}
