package io.github.mbanucu.wirelessadbkeeper;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;

/**
 * A minimal ADB client: enough of the wire protocol to authenticate and run
 * {@code shell} and service requests against adbd over TCP.
 *
 * Deliberately narrow. There is no sync protocol, no file transfer, no USB, no
 * TLS yet (that is M3) and no key storage (M2). What it does have is the part
 * every later milestone shares: the packet framing, the {@code CNXN} handshake,
 * the {@code AUTH} exchange and a service stream.
 *
 * The transport is plain {@link Socket} on purpose: M3 wraps it in a TLS socket
 * without touching this class.
 *
 * Scope note: opening a service requires a key adbd already trusts. Until M2
 * gives the app one, this is verified from the host side with Termux's key.
 */
public final class AdbClient implements Closeable {
    /** ADB strings are UTF-8; shell output is not limited to ASCII. */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    private static final int HEADER = 24;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AdbKey key;
    private int nextId = 1;
    private boolean sentSignature;
    private String banner = "";

    private AdbClient(Socket socket, AdbKey key) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.key = key;
    }

    /**
     * Connects and completes the handshake, so a returned client is authorised.
     *
     * @throws IOException when the transport fails or adbd refuses the key —
     *         in which case the message says so rather than reading as a timeout
     */
    public static AdbClient connect(String host, int port, AdbKey key, int timeoutMs)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            AdbClient client = new AdbClient(socket, key);
            client.handshake();
            return client;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private void handshake() throws IOException {
        // No feature flags: legacy shell output is easier to consume, and
        // nothing here needs shell_v2's exit-code framing.
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(UTF8));
        int authAttempts = 0;
        while (true) {
            Message m = read();
            if (m.command == A_CNXN) {
                banner = payloadString(m);
                return;
            }
            if (m.command != A_AUTH) {
                throw new IOException("unexpected " + commandName(m.command) + " during handshake");
            }
            if (m.arg0 == AUTH_TOKEN) {
                if (++authAttempts > 2) {
                    throw new IOException("adbd kept asking for authentication");
                }
                if (!sentSignature) {
                    sentSignature = true;
                    send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.payload));
                } else {
                    // adbd did not recognise the key; offer it for authorisation.
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload());
                }
            } else {
                throw new IOException("adbd rejected the key (" + commandName(m.command) + ")");
            }
        }
    }

    /** The {@code CNXN} banner, e.g. {@code device::ro.product.name=...}. */
    public String banner() {
        return banner;
    }

    /** The key this client authenticated with, for diagnostics. */
    public String keyName() {
        return key.publicKeyBase64().substring(0, 8);
    }

    /**
     * Opens a service the way {@code adb} spells it, e.g. {@code shell:getprop}
     * or {@code tcpip:5555}, and returns its combined output.
     */
    public String service(String destination) throws IOException {
        int localId = nextId++;
        send(A_OPEN, localId, 0, (destination + "\0").getBytes(UTF8));
        StringBuilder output = new StringBuilder();
        int remoteId = -1;
        while (true) {
            Message m = read();
            // Every packet names the client stream in arg1. adbd can close a
            // stream more than once and a close for the previous service can
            // arrive after the next OPEN; matching the id keeps one command's
            // output out of the next command's result.
            if (m.arg1 != localId) {
                continue;
            }
            if (m.command == A_OKAY) {
                remoteId = m.arg0;
            } else if (m.command == A_WRTE) {
                output.append(new String(m.payload, UTF8));
                if (remoteId >= 0) {
                    send(A_OKAY, localId, remoteId, EMPTY);
                }
            } else if (m.command == A_CLSE) {
                return output.toString();
            } else {
                throw new IOException("unexpected " + commandName(m.command)
                        + " on service " + destination);
            }
        }
    }

    /** Runs a shell command and returns its raw (merged) output. */
    public String shell(String command) throws IOException {
        return service("shell:" + command);
    }

    /**
     * Asks adbd to listen on a fixed TCP port ({@code adb tcpip}).
     *
     * <p>adbd acknowledges this service by restarting, which tears down the
     * transport that sent the request. A closed socket after the request is
     * therefore the acknowledgement, not a failure — the caller has to
     * reconnect before it can tell whether the new port came up.
     *
     * @return true when adbd acknowledged the request
     */
    public boolean tcpip(int port) throws IOException {
        try {
            service("tcpip:" + port);
            return true;
        } catch (EOFException e) {
            return true;
        } catch (SocketException e) {
            // "Connection reset" / "Broken pipe" while adbd restarts. Timeouts
            // and protocol errors are still reported to the caller.
            return true;
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final byte[] EMPTY = new byte[0];

    private void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new IOException("payload too large: " + payload.length);
        }
        byte[] header = new byte[HEADER];
        putInt(header, 0, command);
        putInt(header, 4, arg0);
        putInt(header, 8, arg1);
        putInt(header, 12, payload.length);
        putInt(header, 16, checksum(payload));
        putInt(header, 20, command ^ 0xffffffff);
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[HEADER];
        readFully(header, header.length);
        int command = getInt(header, 0);
        int arg0 = getInt(header, 4);
        int arg1 = getInt(header, 8);
        int length = getInt(header, 12);
        int magic = getInt(header, 20);
        if (magic != (command ^ 0xffffffff)) {
            throw new IOException("bad packet magic for " + commandName(command));
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("bad payload length " + length);
        }
        byte[] payload = new byte[length];
        readFully(payload, length);
        return new Message(command, arg0, arg1, payload);
    }

    private void readFully(byte[] buffer, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(buffer, done, length - done);
            if (n < 0) {
                throw new EOFException("adbd closed the connection");
            }
            done += n;
        }
    }

    private static int checksum(byte[] payload) {
        int sum = 0;
        for (byte b : payload) {
            sum += b & 0xff;
        }
        return sum;
    }

    private static void putInt(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }

    private static int getInt(byte[] in, int offset) {
        return (in[offset] & 0xff)
                | (in[offset + 1] & 0xff) << 8
                | (in[offset + 2] & 0xff) << 16
                | (in[offset + 3] & 0xff) << 24;
    }

    private static String payloadString(Message m) {
        int end = m.payload.length;
        while (end > 0 && m.payload[end - 1] == 0) {
            end--;
        }
        return new String(m.payload, 0, end, UTF8);
    }

    private static String commandName(int command) {
        byte[] b = new byte[4];
        putInt(b, 0, command);
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(x >= 0x20 && x < 0x7f ? (char) x : '.');
        }
        return s.toString();
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] payload;

        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }
}
