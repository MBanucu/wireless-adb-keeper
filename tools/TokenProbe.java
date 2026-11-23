package io.github.mbanucu.wirelessadbkeeper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

/**
 * Host-side probe for {@link ControlToken}: mints in a directory, reloads, and
 * checks the parts that need no device (32 raw bytes, 0600, hex reply, constant
 * comparison outcome, corrupt-file behaviour).
 *
 * <p>The real location is device-protected app storage, which needs Android and
 * is therefore checked on the phone. The broadcast transport, the shell uid
 * gate and the force-stop behaviour also need the installed APK.
 */
public final class TokenProbe {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "/tmp/control-token-probe");
        deleteTree(dir);

        int failures = 0;
        String first = ControlToken.loadOrCreate(dir);
        File file = new File(dir, ControlToken.FILE);
        byte[] raw = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
        System.out.println("file      " + file + " bytes=" + raw.length);
        failures += check("token file is 32 raw bytes", raw.length == ControlToken.BYTES);
        failures += check("token file is 0600", perms(file).equals(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        failures += check("token directory is 0700", perms(dir).equals(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE)));
        failures += check("issued token is 64 lowercase hex characters",
                first.matches("[0-9a-f]{64}"));

        String second = ControlToken.loadOrCreate(dir);
        failures += check("reload returns the same token", first.equals(second));
        failures += check("reload leaves the file alone",
                Arrays.equals(raw, Files.readAllBytes(Paths.get(file.getAbsolutePath()))));

        File other = new File(dir.getAbsolutePath() + "-other");
        deleteTree(other);
        String different = ControlToken.loadOrCreate(other);
        failures += check("a second install mints a different token", !first.equals(different));
        deleteTree(other);

        failures += check("the issued token is accepted", ControlToken.matches(raw, first));
        failures += check("a token with one changed character is refused",
                !ControlToken.matches(raw, flip(first)));
        failures += check("an uppercase token is refused",
                !ControlToken.matches(raw, first.toUpperCase()));
        failures += check("a truncated token is refused",
                !ControlToken.matches(raw, first.substring(0, 63)));
        failures += check("an absent token is refused", !ControlToken.matches(raw, null));
        failures += check("an empty token is refused", !ControlToken.matches(raw, ""));
        failures += check("no stored token refuses a guess", !ControlToken.matches(null, first));

        File broken = new File(dir.getAbsolutePath() + "-broken");
        deleteTree(broken);
        if (!broken.mkdirs()) {
            throw new IOException("could not create " + broken);
        }
        Files.write(Paths.get(new File(broken, ControlToken.FILE).getAbsolutePath()),
                new byte[ControlToken.BYTES - 1]);
        failures += check("a short token file is reported, not replaced",
                loadFailsWithoutReplacing(broken));

        deleteTree(broken);
        deleteTree(dir);
        System.out.println(failures == 0 ? "OK" : failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String flip(String token) {
        char last = token.charAt(token.length() - 1);
        char replacement = last == '0' ? '1' : '0';
        return token.substring(0, token.length() - 1) + replacement;
    }

    private static boolean loadFailsWithoutReplacing(File dir) {
        File file = new File(dir, ControlToken.FILE);
        byte[] before;
        try {
            before = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
        } catch (IOException e) {
            return false;
        }
        try {
            ControlToken.loadOrCreate(dir);
            return false;
        } catch (IOException expected) {
            try {
                return before.length == ControlToken.BYTES - 1
                        && Arrays.equals(before,
                        Files.readAllBytes(Paths.get(file.getAbsolutePath())));
            } catch (IOException e) {
                return false;
            }
        }
    }

    private static Set<PosixFilePermission> perms(File file) throws Exception {
        return Files.getPosixFilePermissions(Paths.get(file.getAbsolutePath()));
    }

    private static int check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
